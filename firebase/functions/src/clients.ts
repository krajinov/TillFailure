import { FieldPath, Firestore, Timestamp } from "firebase-admin/firestore";
import { verifiedEmulatorIdentity, INVITATION_FIXTURE_POLICY } from "./invitations.js";
import { requireProvisioningEmulators } from "./provisioning.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { validBasics } from "./onboarding.js";
import { ClientCursorKeys, CursorScope, CursorState, Position, CURSOR_LIFETIME_MS } from "./client-cursor.js";
import { SPIKE_PROJECT_ID } from "./environment.js";

export type ClientRow = { clientId: string; workspaceId: string; displayName: string | null;
  relationshipStatus: "active"; onboardingStatus: "pending" | "complete" };
export const CLIENT_READ_BUDGET = Object.freeze({ cap: INVITATION_FIXTURE_POLICY.maxMembershipsPerWorkspace,
  maxReads: 20, maxBytes: 20 * 1_048_576, deadlineMs: 5000, maxAttempts: 3 });
type Budget = { cap: number; maxReads: number; maxBytes: number; deadlineMs: number; maxAttempts: number };
// Discovery/production processes get no demo key. A guarded emulator worker owns its
// random generation from startup, so even an idle key rotates on the wall-clock bound.
const keys = (() => {
  try { requireProvisioningEmulators(); return new ClientCursorKeys(); }
  catch { return undefined; }
})();
const fail = (reason: string): never => { throw new Error(reason); };
const revision = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 1 && (v as number) <= 2147483646;
const counter = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;
const accountFields = ["schemaVersion", "accountStatus", "lifecycleRevision", "emailNormalized", "displayName", "locale", "timeZoneId", "profileRevision"];
const targetAccountFields = accountFields.filter(field => field !== "emailNormalized");
const memberFields = ["schemaVersion", "workspaceId", "userId", "role", "status", "revision", "catalogContributionActive"];
const profileFields = ["schemaVersion", "workspaceId", "userId", "trainerId", "createdAt", "onboardingStatus", "onboardingRevision", "onboardingPolicyVersion", "completedAt"];
type Data = Record<string, any>;
function member(d: Data | undefined, wid: string, uid: string) {
  participantUid(uid, "uid");
  if (!d || d.schemaVersion !== 1 || d.workspaceId !== wid || d.userId !== uid ||
      !["trainer", "client"].includes(d.role) || d.status !== "active" || !revision(d.revision) || d.catalogContributionActive !== true) fail("unavailable");
}
function activeAccount(d: Data | undefined) { return !!d && d.schemaVersion === 1 && d.accountStatus === "active" && revision(d.lifecycleRevision); }
function profile(d: Data | undefined, wid: string, uid: string) {
  if (!d || d.schemaVersion !== 1 || d.workspaceId !== wid || d.userId !== uid || !(d.createdAt instanceof Timestamp) || d.createdAt.seconds < 0 ||
      !["pending", "complete"].includes(d.onboardingStatus)) fail("unavailable");
  participantUid(d!.trainerId, "trainerId");
  const r = d!.onboardingRevision === undefined && d!.onboardingStatus === "pending" ? 0 : d!.onboardingRevision;
  if (!counter(r) || r > 2147483646 || d!.onboardingStatus === "pending" && (d!.completedAt !== undefined || d!.onboardingPolicyVersion !== undefined) ||
      d!.onboardingStatus === "complete" && (!revision(r) || d!.onboardingPolicyVersion !== 1 || !(d!.completedAt instanceof Timestamp))) fail("unavailable");
}
function name(a: Data, p: Data): string | null {
  const empty = (a.displayName === undefined || a.displayName === "") && a.locale === undefined && a.timeZoneId === undefined;
  const r = a.profileRevision === undefined && empty ? 0 : a.profileRevision;
  if (r === 0 && empty && p.onboardingStatus === "pending") return null;
  if (!revision(r) || !validBasics(a)) fail("unavailable");
  return a.displayName;
}
export const comparePosition = (a: Position, b: Position) => a.seconds - b.seconds || a.nanoseconds - b.nanoseconds || (a.clientId < b.clientId ? -1 : a.clientId > b.clientId ? 1 : 0);

/** A complete bounded active set, never a scan of historical profiles. No anchor lookup. */
export async function clientOperation(db: Firestore, caller: string | undefined, raw: unknown,
  cursorKeys = keys, clock: () => number = Date.now, budget: Budget = CLIENT_READ_BUDGET) {
  requireProvisioningEmulators();
  const issuer = cursorKeys ?? fail("unavailable");
  // Refuse undersized configurations before inspecting any dataset, independently of page size.
  const requiredReads = 3 * budget.cap + 4;
  if (![budget.cap, budget.maxReads, budget.maxBytes, budget.deadlineMs, budget.maxAttempts].every(Number.isSafeInteger) || !Number.isInteger(budget.cap) || budget.cap < 1 || budget.cap > 99 || budget.maxReads < requiredReads ||
      budget.maxBytes < requiredReads * 1_048_576 || budget.deadlineMs < 1 || budget.deadlineMs > 5000 ||
      budget.maxAttempts < 1 || budget.maxAttempts > 3) fail("unavailable");
  if (!caller) fail("unauthenticated");
  const uid = participantUid(caller, "uid");
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) fail("input-invalid");
  const d = raw as Data;
  const fields = d.action === "list" ? ["action", "workspaceId", "pageSize", "cursor"] : d.action === "get" ? ["action", "workspaceId", "clientId"] : [];
  if (!fields.length || Object.keys(d).sort().join() !== fields.sort().join()) fail("input-invalid");
  const wid = participantUid(strictPathIdentifier(d.workspaceId, "workspaceId"), "workspaceId");
  if (d.action === "list" && (!Number.isInteger(d.pageSize) || d.pageSize < 1 || d.pageSize > 20)) fail("input-invalid");
  const target = d.action === "get" ? participantUid(d.clientId, "clientId") : undefined;
  // Fresh Admin Auth and then transaction authority always precede cursor handling.
  const user = await verifiedEmulatorIdentity(uid);
  const scope: CursorScope = { project: SPIKE_PROJECT_ID, environment: "loopback-emulator", uid, trainer: uid, workspace: wid, pageSize: d.pageSize };
  const start = clock();
  const checkDeadline = () => { if (clock() - start >= budget.deadlineMs) fail("unavailable"); };
  const work = db.runTransaction(async tx => {
    checkDeadline();
    const get = async (path: string, fieldMask: string[]) => (await tx.getAll(db.doc(path), { fieldMask }))[0]!.data();
    const a = await get(`users/${uid}`, accountFields), w = await get(`workspaces/${wid}`, ["schemaVersion", "status", "membershipRevision", "activeRosterCount", "catalogContributionCount"]);
    const m = await get(`workspaces/${wid}/memberships/${uid}`, memberFields);
    if (!activeAccount(a) || a!.emailNormalized !== user.email!.toLowerCase() || !w || w.schemaVersion !== 1 || w.status !== "active" ||
        !revision(w.membershipRevision) || !m || m.role !== "trainer") fail("unauthorized");
    try { member(m, wid, uid); } catch { fail("unauthorized"); }
    let cursor: CursorState | undefined;
    if (d.action === "list" && d.cursor !== null) {
      cursor = issuer.open(scope, d.cursor);
      try { participantUid(cursor.last.clientId, "clientId"); } catch { fail("invalid-cursor"); }
      if (cursor.revision !== m!.revision) fail("invalid-cursor");
    }
    checkDeadline();
    async function row(clientId: string, membership: Data): Promise<{ item: ClientRow; position: Position } | null> {
      member(membership, wid, clientId);
      if (membership.role !== "client") return null;
      const p = await get(`workspaces/${wid}/clientProfiles/${clientId}`, profileFields);
      profile(p, wid, clientId);
      if (p!.trainerId !== uid) return null;
      const account = await get(`users/${clientId}`, targetAccountFields);
      if (!account) fail("unavailable");
      if (!activeAccount(account)) {
        if (account!.schemaVersion === 1 && account!.accountStatus === "disabled" && revision(account!.lifecycleRevision)) return null;
        fail("unavailable");
      }
      return { item: { clientId, workspaceId: wid, displayName: name(account!, p!), relationshipStatus: "active", onboardingStatus: p!.onboardingStatus },
        position: { seconds: p!.createdAt.seconds, nanoseconds: p!.createdAt.nanoseconds, clientId } };
    }
    if (target) {
      const membership = await get(`workspaces/${wid}/memberships/${target}`, memberFields);
      if (!membership || membership.status !== "active" || membership.role !== "client") fail("unauthorized");
      const r = await row(target, membership!);
      checkDeadline();
      if (!r) fail("unauthorized");
      return r!.item;
    }
    const active = await tx.get(db.collection(`workspaces/${wid}/memberships`).where("status", "==", "active")
      .orderBy(FieldPath.documentId()).limit(budget.cap + 1).select(...memberFields));
    if (active.size > budget.cap || !counter(w!.activeRosterCount) || active.size !== w!.activeRosterCount || w!.catalogContributionCount !== active.size) fail("unavailable");
    const rows = [];
    for (const doc of active.docs) { checkDeadline(); const r = await row(doc.id, doc.data()); if (r) rows.push(r); }
    checkDeadline();
    const eligible = rows.sort((a, b) => comparePosition(a.position, b.position)).filter(r => !cursor || comparePosition(r.position, cursor.last) > 0);
    const page = eligible.slice(0, d.pageSize);
    const now = clock();
    // Traversal expiry never slides; a service delay cannot return an already expired continuation.
    if (cursor && cursor.expiresAt <= now) fail("invalid-cursor");
    const nextCursor = eligible.length > page.length ? issuer.seal(scope, { version: 2, purpose: "clients-list", filter: "assigned-active", order: "createdAt-id-v2",
      uid, trainer: uid, workspace: wid, pageSize: d.pageSize, revision: m!.revision,
      issuedAt: cursor?.issuedAt ?? now, expiresAt: cursor?.expiresAt ?? now + CURSOR_LIFETIME_MS, last: page[page.length - 1]!.position }) : null;
    return { items: page.map(r => r.item), nextCursor };
  }, { maxAttempts: budget.maxAttempts });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try { return await Promise.race([work, new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error("unavailable")), budget.deadlineMs); })]); }
  finally { clearTimeout(timer); }
}
export function clientFailure(error: unknown): { code: "unauthenticated" | "permission-denied" | "failed-precondition" | "invalid-argument" | "unavailable"; reason: string } {
  const reason = error instanceof Error ? error.message : "";
  if (reason === "unauthenticated") return { code: "unauthenticated", reason };
  if (["unauthorized", "unverified-email"].includes(reason)) return { code: "permission-denied", reason: "unauthorized" };
  if (reason === "invalid-cursor") return { code: "failed-precondition", reason };
  if (reason === "input-invalid") return { code: "invalid-argument", reason };
  return { code: "unavailable", reason: "unavailable" };
}
