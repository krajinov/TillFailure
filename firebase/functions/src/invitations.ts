import { randomBytes, createHash, timingSafeEqual } from "node:crypto";
import { getAuth, UserRecord } from "firebase-admin/auth";
import { Firestore, Timestamp, Transaction, DocumentSnapshot } from "firebase-admin/firestore";
import { projectMembershipCounters } from "./catalog.js";
import { commandId, stableHash } from "./hashing.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { requireProvisioningEmulators } from "./provisioning.js";

// Versioned LOCAL FIXTURE ONLY. No production policy or delivery provider is approved.
export const INVITATION_FIXTURE_POLICY = Object.freeze({ version: 1, lifetimeMillis: 48 * 60 * 60 * 1000,
  maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5, maxRequestsPerMinute: 30 });
export type InvitationAction = "create" | "list" | "preview" | "resend" | "revoke" | "accept";
type Input = { action: InvitationAction; workspaceId?: string; invitationId?: string; email?: string;
  token?: string; expectedVersion?: number; idempotencyKey?: string };
type Summary = { invitationId: string; emailMasked: string; role: "client"; status: string;
  expiresAtMillis: number; createdAtMillis: number; lastSentAtMillis: number; tokenVersion: number };
export type InvitationResult = { invitationId?: string; workspaceId?: string; workspaceName?: string;
  status?: string; tokenVersion?: number; expiresAtMillis?: number; replayed?: boolean;
  deliveryToken?: string; summaries?: Summary[] };
/** Unexpected backend failures can follow an uncertain commit and must not settle mobile work. */
export function invitationFailure(error: unknown): { code: "unauthenticated" | "permission-denied" | "resource-exhausted" | "failed-precondition" | "internal"; reason: string } {
  const reason = error instanceof Error ? error.message : "";
  const known = ["unauthenticated", "unauthorized", "unverified-email", "wrong-account-email", "expired",
    "rotated", "revoked", "already-used", "idempotency-key-reused", "membership-conflict", "rate-limited",
    "fixture-account-required", "record-invalid", "counter-inconsistent", "input-invalid", "email-invalid", "token-invalid",
    "membership-bound-violated", "workspace-roster-bound-violated", "invitation-unavailable", "invitation-conflict"];
  if (!known.includes(reason)) return { code: "internal", reason: "invitation-unavailable" };
  return { code: reason === "unauthenticated" ? "unauthenticated" : reason === "unauthorized" ? "permission-denied" :
    reason === "rate-limited" ? "resource-exhausted" : "failed-precondition", reason };
}
function fail(reason: string): never { throw new Error(reason); }
const integer = (n: unknown): number => typeof n === "number" && Number.isSafeInteger(n) && n >= 1 ? n : fail("record-invalid");
const digest = (token: string): string => createHash("sha256").update(token).digest("hex");
function email(value: unknown): string {
  if (typeof value !== "string" || value.length > 254 || value.trim() !== value ||
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) fail("email-invalid");
  return (value as string).toLowerCase();
}
function input(raw: unknown): Input {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) fail("input-invalid");
  const data = raw as Record<string, unknown>;
  const fields: Record<string, string[]> = {
    create: ["workspaceId", "email", "idempotencyKey"], list: ["workspaceId"],
    preview: ["invitationId", "token", "expectedVersion"], accept: ["invitationId", "token", "expectedVersion", "idempotencyKey"],
    resend: ["workspaceId", "invitationId", "expectedVersion", "idempotencyKey"],
    revoke: ["workspaceId", "invitationId", "expectedVersion", "idempotencyKey"]
  };
  const required = typeof data.action === "string" ? fields[data.action] : undefined;
  if (!required || Object.keys(data).length !== required.length + 1 ||
      Object.keys(data).some(k => k !== "action" && !required.includes(k))) fail("input-invalid");
  const result: Input = { action: data.action as InvitationAction };
  for (const field of required) {
    if (field === "email") result.email = email(data.email);
    else if (field === "token") {
      if (typeof data.token !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(data.token)) fail("token-invalid");
      result.token = data.token as string;
    } else if (field === "expectedVersion") result.expectedVersion = integer(data.expectedVersion);
    else Object.assign(result, { [field]: participantUid(strictPathIdentifier(data[field], field), field) });
  }
  return result;
}
async function identity(uid: string, verified = true): Promise<UserRecord> {
  const user = await getAuth().getUser(participantUid(uid, "uid")).catch(() => fail("unauthorized"));
  if (user.disabled) fail("unauthorized");
  if (verified && !user.emailVerified) fail("unverified-email");
  email(user.email);
  return user;
}
function account(doc: DocumentSnapshot, user: UserRecord): void {
  if (!doc.exists || doc.get("schemaVersion") !== 1 || doc.get("accountStatus") !== "active" ||
      doc.get("emailNormalized") !== email(user.email)) fail("unauthorized");
  integer(doc.get("lifecycleRevision"));
}
function member(doc: DocumentSnapshot, wid: string): void {
  participantUid(doc.id, "memberUid");
  if (!doc.exists || doc.get("schemaVersion") !== 1 || doc.get("userId") !== doc.id ||
      doc.get("workspaceId") !== wid || doc.get("status") !== "active" ||
      !["trainer", "client"].includes(doc.get("role")) || typeof doc.get("catalogContributionActive") !== "boolean") fail("unauthorized");
  integer(doc.get("revision"));
}
async function trainer(tx: Transaction, db: Firestore, wid: string, user: UserRecord): Promise<DocumentSnapshot> {
  const [a, w, m, profile, discovery, entitlement] = await tx.getAll(db.doc(`users/${user.uid}`), db.doc(`workspaces/${wid}`),
    db.doc(`workspaces/${wid}/memberships/${user.uid}`), db.doc(`workspaces/${wid}/trainerProfiles/${user.uid}`),
    db.doc(`users/${user.uid}/membershipRefs/${wid}`), db.doc(`users/${user.uid}/authorizations/systemCatalog`));
  account(a!, user); member(m!, wid);
  if (m!.get("role") !== "trainer" || m!.get("catalogContributionActive") !== true || w!.get("schemaVersion") !== 1 ||
      w!.get("status") !== "active" || w!.get("ownerUid") !== user.uid || typeof w!.get("name") !== "string" ||
      w!.get("name").length < 1 || w!.get("name").length > 80) fail("unauthorized");
  integer(w!.get("membershipRevision"));
  if (profile!.get("schemaVersion") !== 1 || profile!.get("workspaceId") !== wid || profile!.get("userId") !== user.uid ||
      discovery!.get("schemaVersion") !== 1 || discovery!.get("status") !== "active" ||
      entitlement!.get("schemaVersion") !== 1 || entitlement!.get("status") !== "active") fail("record-invalid");
  integer(entitlement!.get("revision"));
  const roster = await tx.get(db.collection(`workspaces/${wid}/memberships`).where("status", "==", "active").limit(6));
  roster.docs.forEach(m => member(m, wid));
  if (roster.size > INVITATION_FIXTURE_POLICY.maxMembershipsPerWorkspace || roster.size !== w!.get("activeRosterCount") ||
      roster.docs.some(m => m.get("catalogContributionActive") !== true) || roster.size !== w!.get("catalogContributionCount")) fail("counter-inconsistent");
  const contributions = await accountContributions(tx, db, user.uid);
  if (contributions.count !== entitlement!.get("activeMembershipCount") || contributions.count > INVITATION_FIXTURE_POLICY.maxMembershipsPerAccount) fail("counter-inconsistent");
  return w!;
}
async function accountContributions(tx: Transaction, db: Firestore, uid: string): Promise<{ count: number; references: number }> {
  const refs = await tx.get(db.collection(`users/${uid}/membershipRefs`).limit(21));
  if (refs.size > 20) fail("counter-inconsistent");
  // The directory is not the authority inventory. A retained active membership without its
  // reference must not be omitted from caps/entitlements when a new invitation is accepted.
  const memberships = await tx.get(db.collectionGroup("memberships").where("userId", "==", uid)
    .where("status", "==", "active").limit(21));
  const expectedPaths = new Set(refs.docs.map(ref => `workspaces/${ref.id}/memberships/${uid}`));
  if (memberships.size !== refs.size || memberships.docs.some(m => !expectedPaths.has(m.ref.path))) fail("counter-inconsistent");
  let contributions = 0;
  for (const ref of refs.docs) {
    participantUid(strictPathIdentifier(ref.id, "workspaceId"), "workspaceId");
    if (ref.get("schemaVersion") !== 1 || ref.get("status") !== "active") fail("record-invalid");
    const [w, m] = await tx.getAll(db.doc(`workspaces/${ref.id}`), db.doc(`workspaces/${ref.id}/memberships/${uid}`));
    member(m!, ref.id);
    if (w!.get("schemaVersion") !== 1 || !["active", "suspended"].includes(w!.get("status")) ||
        m!.get("catalogContributionActive") !== (w!.get("status") === "active")) fail("counter-inconsistent");
    contributions += Number(m!.get("catalogContributionActive"));
  }
  return { count: contributions, references: refs.size };
}
function summary(doc: DocumentSnapshot): Summary {
  const fields = ["schemaVersion", "invitationId", "emailMasked", "role", "status", "expiresAt", "createdAt", "lastSentAt", "tokenVersion"];
  const data = doc.data();
  if (!data || Object.keys(data).some(k => !fields.includes(k)) || data.schemaVersion !== 1 ||
      data.invitationId !== doc.id || data.role !== "client" || !["pending", "accepted", "revoked"].includes(data.status) ||
      typeof data.emailMasked !== "string" || data.emailMasked.length > 254 ||
      ![data.expiresAt, data.createdAt, data.lastSentAt].every(t => t instanceof Timestamp)) fail("record-invalid");
  return { invitationId: doc.id, emailMasked: data.emailMasked, role: "client", status: data.status,
    expiresAtMillis: data.expiresAt.toMillis(), createdAtMillis: data.createdAt.toMillis(),
    lastSentAtMillis: data.lastSentAt.toMillis(), tokenVersion: integer(data.tokenVersion) };
}
function invitation(doc: DocumentSnapshot): FirebaseFirestore.DocumentData {
  const d = doc.data();
  if (!d || d.schemaVersion !== 1 || d.policyVersion !== 1 || d.invitationId !== doc.id || d.role !== "client" ||
      !["pending", "accepted", "revoked"].includes(d.status) || !(d.expiresAt instanceof Timestamp) ||
      !(d.createdAt instanceof Timestamp) || !(d.lastSentAt instanceof Timestamp) ||
      typeof d.tokenHash !== "string" || !/^[a-f0-9]{64}$/.test(d.tokenHash)) fail("invitation-unavailable");
  participantUid(d.invitedBy, "inviter"); participantUid(d.invitedUid, "invitedUid");
  participantUid(strictPathIdentifier(d.workspaceId, "workspaceId"), "workspaceId");
  if (email(d.invitedEmailNormalized) !== d.invitedEmailNormalized) fail("record-invalid");
  integer(d.tokenVersion);
  if (d.status === "accepted" && (d.acceptedBy !== d.invitedUid || !(d.acceptedAt instanceof Timestamp))) fail("record-invalid");
  return d;
}
function consistent(d: FirebaseFirestore.DocumentData, s: Summary): void {
  if (s.status !== d.status || s.tokenVersion !== d.tokenVersion || s.emailMasked !== mask(d.invitedEmailNormalized) ||
      s.expiresAtMillis !== d.expiresAt.toMillis() || s.createdAtMillis !== d.createdAt.toMillis() ||
      s.lastSentAtMillis !== d.lastSentAt.toMillis()) fail("record-invalid");
}
const mask = (value: string): string => `${value[0]}***@${value.split("@")[1]}`;

/** Auth caller is supplied only by the verified callable context; no client identity fields exist. */
export async function invitationOperation(db: Firestore, callerUid: string | undefined, raw: unknown): Promise<InvitationResult> {
  requireProvisioningEmulators();
  if (!callerUid) fail("unauthenticated");
  const request = input(raw);
  const user = await identity(callerUid!);
  // Bounded per-UID throttle also protects token preview. No token or email enters this record.
  await db.runTransaction(async tx => {
    const ref = db.doc(`invitationRateLimits/${stableHash(user.uid)}`);
    const old = await tx.get(ref); const minute = Math.floor(Date.now() / 60000);
    const count = old.get("minute") === minute ? old.get("count") : 0;
    if (!Number.isSafeInteger(count) || count < 0 || count >= INVITATION_FIXTURE_POLICY.maxRequestsPerMinute) fail("rate-limited");
    tx.set(ref, { minute, count: count + 1 });
  });
  const iid = request.action === "create" ? commandId(user.uid, request.idempotencyKey!) : request.invitationId;
  // Resolve inviter from the immutable internal ID, then reload Auth before transaction authorization.
  const initial = iid && request.action !== "create" ? await db.doc(`invitations/${iid}`).get() : undefined;
  const original = initial ? invitation(initial) : undefined;
  const inviter = original ? await identity(original.invitedBy) : user;
  const target = request.action === "create" ? await getAuth().getUserByEmail(request.email!).catch(() => fail("fixture-account-required")) : undefined;
  if (target?.disabled) fail("unauthorized");
  if (target) participantUid(target.uid, "invitedUid");
  const token = ["create", "resend"].includes(request.action) ? randomBytes(32).toString("base64url") : undefined;
  // Request hashes contain token hashes only, never bearer material. Receipts contain safe results.
  const requestHash = stableHash({ ...request, token: request.token ? digest(request.token) : null, callerUid: user.uid });
  return db.runTransaction(async tx => {
    const now = Timestamp.now();
    const wid = request.workspaceId ?? original?.workspaceId;
    if (!wid) fail("input-invalid");
    if (original && (original.workspaceId !== wid || original.invitedBy !== inviter.uid)) fail("unauthorized");
    const workspace = await trainer(tx, db, wid!, ["create", "list", "resend", "revoke"].includes(request.action) ? user : inviter);
    const callerAccount = await tx.get(db.doc(`users/${user.uid}`));
    if (callerAccount.exists) account(callerAccount, user);
    else if (!["preview", "accept"].includes(request.action)) fail("unauthorized");
    if (request.action === "list") {
      const docs = await tx.get(db.collection(`workspaces/${wid}/invitationSummaries`).orderBy("createdAt", "desc").limit(20));
      return { summaries: docs.docs.map(summary) };
    }
    const inviteRef = db.doc(`invitations/${iid}`);
    const summaryRef = db.doc(`workspaces/${wid}/invitationSummaries/${iid}`);
    const [stored, projection] = await tx.getAll(inviteRef, summaryRef);
    const d = stored!.exists ? invitation(stored!) : undefined;
    if (d) {
      if (d.workspaceId !== wid || d.invitedBy !== inviter.uid) fail("unauthorized");
      consistent(d, summary(projection!));
    } else if (request.action !== "create" || projection!.exists) fail("invitation-unavailable");
    const accepting = ["preview", "accept"].includes(request.action);
    if (accepting && (d!.invitedUid !== user.uid || d!.invitedEmailNormalized !== email(user.email))) fail("wrong-account-email");
    const receiptRef = request.idempotencyKey ? db.doc(`lifecycleCommands/${commandId(user.uid, request.idempotencyKey)}`) : undefined;
    const receipt = receiptRef ? await tx.get(receiptRef) : undefined;
    if (receipt?.exists) {
      if (receipt.get("schemaVersion") !== 1 || receipt.get("commandKind") !== `invitation-${request.action}` ||
          receipt.get("callerUid") !== user.uid || receipt.get("requestHash") !== requestHash ||
          receipt.get("invitationId") !== iid || receipt.get("workspaceId") !== wid) fail("idempotency-key-reused");
      const r = receipt.get("result");
      const committedAt = receipt.get("committedAt");
      const expectedStatus = request.action === "accept" ? "accepted" : request.action === "revoke" ? "revoked" : "pending";
      const expectedVersion = request.action === "create" ? 1 : request.action === "resend" ? request.expectedVersion! + 1 : request.expectedVersion;
      const expectedExpiry = ["create", "resend"].includes(request.action) && committedAt instanceof Timestamp
        ? committedAt.toMillis() + INVITATION_FIXTURE_POLICY.lifetimeMillis : d!.expiresAt.toMillis();
      if (!r || Object.keys(r).sort().join(",") !== "expiresAtMillis,invitationId,status,tokenVersion,workspaceId" ||
          r.invitationId !== iid || r.workspaceId !== wid || r.status !== expectedStatus ||
          !(committedAt instanceof Timestamp) || r.expiresAtMillis !== expectedExpiry ||
          r.tokenVersion !== expectedVersion || integer(r.tokenVersion) > d!.tokenVersion) fail("record-invalid");
      if (request.action === "accept") {
        const m = await tx.get(db.doc(`workspaces/${wid}/memberships/${user.uid}`));
        member(m, wid!);
        if (m.get("role") !== "client" || m.get("invitationId") !== iid || d!.status !== "accepted" ||
            d!.acceptedBy !== user.uid) fail("unauthorized");
      }
      return { ...r, replayed: true }; // Tokens cannot be reconstructed from stored hashes.
    }
    if (request.action === "create" && d) fail("invitation-conflict");
    if (d) {
      if (d.tokenVersion !== request.expectedVersion) fail("rotated");
      if (accepting && !timingSafeEqual(Buffer.from(d.tokenHash, "hex"), Buffer.from(digest(request.token!), "hex"))) fail("invitation-unavailable");
      if (d.status === "revoked") fail("revoked");
      if (d.status === "accepted") fail("already-used");
      // Expired pending invitations can be explicitly rotated or revoked; never accepted.
      if (accepting && d.expiresAt.toMillis() <= now.toMillis()) fail("expired");
    }
    if (request.action === "preview") return { invitationId: iid, workspaceId: wid,
      workspaceName: workspace.get("name"), status: "pending", tokenVersion: d!.tokenVersion, expiresAtMillis: d!.expiresAt.toMillis() };

    const version = request.action === "resend" ? integer(d!.tokenVersion + 1) : d?.tokenVersion ?? 1;
    const expiry = token ? Timestamp.fromMillis(now.toMillis() + INVITATION_FIXTURE_POLICY.lifetimeMillis) : d!.expiresAt;
    const status = request.action === "accept" ? "accepted" : request.action === "revoke" ? "revoked" : "pending";
    if (request.action === "accept") {
      const entitlementRef = db.doc(`users/${user.uid}/authorizations/systemCatalog`);
      const membershipRef = db.doc(`workspaces/${wid}/memberships/${user.uid}`);
      const profileRef = db.doc(`workspaces/${wid}/clientProfiles/${user.uid}`);
      const directoryRef = db.doc(`users/${user.uid}/membershipRefs/${wid}`);
      const [entitlement, membership, profile, directory] = await tx.getAll(entitlementRef, membershipRef, profileRef, directoryRef);
      if (membership!.exists || profile!.exists || directory!.exists) fail("membership-conflict");
      // Deleting a relationship parent does not recursively delete its assigned content.
      // A fresh invitation must never make old assignments readable again under that path.
      const retained = await tx.get(db.collection(`users/${user.uid}/workspaces/${wid}/assignedPrograms`).limit(1));
      if (!retained.empty) fail("membership-conflict");
      const roster = await tx.get(db.collection(`workspaces/${wid}/memberships`).where("status", "==", "active").limit(6));
      roster.docs.forEach(m => member(m, wid!));
      if (roster.size !== workspace.get("activeRosterCount") || roster.docs.filter(m => m.get("catalogContributionActive")).length !== workspace.get("catalogContributionCount")) fail("counter-inconsistent");
      const inventory = await accountContributions(tx, db, user.uid);
      const contributions = inventory.count;
      if (callerAccount.exists !== entitlement!.exists || (!callerAccount.exists && inventory.references !== 0)) fail("record-invalid");
      if (entitlement!.exists && (entitlement!.get("schemaVersion") !== 1 ||
          entitlement!.get("status") !== (contributions > 0 ? "active" : "inactive") ||
          entitlement!.get("activeMembershipCount") !== contributions)) fail("counter-inconsistent");
      if (entitlement!.exists) integer(entitlement!.get("revision"));
      const counters = projectMembershipCounters({ accountCount: contributions,
        rosterCount: workspace.get("activeRosterCount"), contributionCount: workspace.get("catalogContributionCount"),
        wasActive: false, becomesActive: true, oldContributes: false, newContributes: true, ...INVITATION_FIXTURE_POLICY });
      if (!callerAccount.exists) tx.create(callerAccount.ref, { schemaVersion: 1, accountStatus: "active",
        emailNormalized: email(user.email), lifecycleRevision: 1, displayName: "", createdAt: now, createdBy: user.uid });
      const entitlementData = { schemaVersion: 1, status: "active", activeMembershipCount: counters.accountCount,
        revision: entitlement!.exists ? entitlement!.get("revision") + 1 : 1, updatedAt: now, updatedBy: user.uid };
      if (entitlement!.exists) tx.update(entitlementRef, entitlementData); else tx.create(entitlementRef, entitlementData);
      tx.create(membershipRef, { schemaVersion: 1, workspaceId: wid, userId: user.uid, role: "client", status: "active",
        revision: 1, catalogContributionActive: true, invitationId: iid, joinedAt: now });
      tx.create(profileRef, { schemaVersion: 1, workspaceId: wid, userId: user.uid, trainerId: inviter.uid,
        onboardingStatus: "pending", createdAt: now });
      tx.create(directoryRef, { schemaVersion: 1, status: "active" });
      tx.update(workspace.ref, { membershipRevision: workspace.get("membershipRevision") + 1,
        activeRosterCount: counters.rosterCount, catalogContributionCount: counters.contributionCount });
    }
    const internal: FirebaseFirestore.DocumentData = request.action === "create" ? { schemaVersion: 1, policyVersion: 1, invitationId: iid,
      workspaceId: wid, invitedBy: user.uid, invitedUid: target!.uid, invitedEmailNormalized: request.email!,
      role: "client", status, tokenHash: digest(token!), tokenVersion: version, expiresAt: expiry,
      createdAt: now, lastSentAt: now } : { ...d, status, tokenVersion: version, expiresAt: expiry,
      ...(token ? { tokenHash: digest(token), lastSentAt: now } : {}),
      ...(status === "accepted" ? { acceptedBy: user.uid, acceptedAt: now } : {}),
      ...(status === "revoked" ? { revokedAt: now } : {}) };
    // Terminal hashes are retained only for receipt integrity; terminal status forbids reuse.
    tx.set(inviteRef, internal);
    tx.set(summaryRef, { schemaVersion: 1, invitationId: iid, emailMasked: mask(internal.invitedEmailNormalized),
      role: "client", status, tokenVersion: version, expiresAt: expiry, createdAt: internal.createdAt, lastSentAt: internal.lastSentAt });
    const result = { invitationId: iid!, workspaceId: wid!, status, tokenVersion: version, expiresAtMillis: expiry.toMillis() };
    tx.create(receiptRef!, { schemaVersion: 1, commandKind: `invitation-${request.action}`, callerUid: user.uid,
      invitationId: iid, workspaceId: wid, requestHash, result, committedAt: now });
    return { ...result, replayed: false, ...(token ? { deliveryToken: token } : {}) };
  });
}
