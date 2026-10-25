import { validNoteSave, noteDecisionValid } from "./trainer-notes.js";
import { validRevocationBinding, revocationDecisionValid } from "./client-revocation.js";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { verifiedEmulatorIdentity, INVITATION_FIXTURE_POLICY } from "./invitations.js";
import { validBasics, ONBOARDING_FIXTURE_POLICY } from "./onboarding.js";
import { requireProvisioningEmulators } from "./provisioning.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { commandId, stableHash } from "./hashing.js";
import { requireCommandGeneration } from "./command-identity.js";

type Data = Record<string, any>;
const fail = (): never => { throw new Error("outcome-unavailable"); };
const exact = (v: unknown, keys: string[]): v is Data => !!v && typeof v === "object" && !Array.isArray(v) && Object.keys(v).sort().join() === [...keys].sort().join();
const integer = (v: unknown, min = 0): v is number => Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= ONBOARDING_FIXTURE_POLICY.maxRevision;
const time = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0 && (v as number) <= 8_640_000_000_000_000;
export function resolutionBinding(uid: string, raw: unknown) {
  if (!exact(raw, ["protocolVersion", "descriptor"]) || raw.protocolVersion !== 1 || !raw.descriptor || typeof raw.descriptor !== "object") fail();
  if (JSON.stringify(raw).length > 8192) fail();
  const d = (raw as Data).descriptor as Data;
  const fields: Record<string, string[]> = { create: ["action", "workspaceId", "email", "idempotencyKey"],
    resend: ["action", "workspaceId", "invitationId", "expectedVersion", "idempotencyKey"],
    revoke: ["action", "workspaceId", "invitationId", "expectedVersion", "idempotencyKey"],
    accept: ["action", "invitationId", "expectedVersion", "idempotencyKey", "acceptanceTokenDigest"],
    "complete-onboarding": ["action", "workspaceId", "idempotencyKey", "onboarding"],
    "revoke-client": ["action", "workspaceId", "idempotencyKey", "revocation"],
    "save-trainer-note": ["action", "workspaceId", "idempotencyKey", "noteSave"],
    "update-profile": ["action", "workspaceId", "idempotencyKey", "profileUpdate"] };
  if (!fields[d.action] || !exact(d, fields[d.action]!)) fail();
  const key = participantUid(strictPathIdentifier(d.idempotencyKey, "key"), "key");
  if (d.workspaceId !== undefined) participantUid(strictPathIdentifier(d.workspaceId, "workspace"), "workspace");
  if (d.invitationId !== undefined && (typeof d.invitationId !== "string" || !/^[a-f0-9]{64}$/.test(d.invitationId))) fail();
  if (d.expectedVersion !== undefined && (!Number.isSafeInteger(d.expectedVersion) || d.expectedVersion < 1 ||
      (d.action === "resend" && d.expectedVersion === Number.MAX_SAFE_INTEGER))) fail();
  const invitation = ["create", "resend", "revoke", "accept"].includes(d.action);
  let request: Data = { ...d };
  if (d.action === "create") {
    if (typeof d.email !== "string" || d.email.length > 254 || d.email.trim() !== d.email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.email)) fail();
    request.email = d.email.toLowerCase();
  }
  if (d.action === "accept") {
    if (typeof d.acceptanceTokenDigest !== "string" || !/^[a-f0-9]{64}$/.test(d.acceptanceTokenDigest)) fail();
    delete request.acceptanceTokenDigest;
  }
  if (d.action === "complete-onboarding") {
    const p = d.onboarding;
    if (!exact(p, ["displayName", "locale", "timeZoneId", "acknowledged", "expectedAccountRevision", "expectedOnboardingRevision"]) ||
        !validBasics(p) || p.acknowledged !== true || !integer(p.expectedAccountRevision) || !integer(p.expectedOnboardingRevision) ||
        p.expectedAccountRevision >= ONBOARDING_FIXTURE_POLICY.maxRevision || p.expectedOnboardingRevision >= ONBOARDING_FIXTURE_POLICY.maxRevision) fail();
  }
  if (d.action === "update-profile") {
    const p = d.profileUpdate;
    if (!exact(p, ["displayName", "locale", "timeZoneId", "expectedRevision"]) || !validBasics(p) || !integer(p.expectedRevision, 1) || p.expectedRevision >= ONBOARDING_FIXTURE_POLICY.maxRevision) fail();
  }
  if (d.action === "save-trainer-note" && !validNoteSave(d.noteSave)) fail();
  if (d.action === "revoke-client" && !validRevocationBinding(d.revocation)) fail();
  const kind = d.action === "save-trainer-note" ? "trainer-note-save" : d.action === "revoke-client" ? "client-membership-revoke" : invitation ? `invitation-${d.action}` : d.action === "complete-onboarding" ? "onboarding-complete" : "profile-update";
  const hashVersion = d.action === "save-trainer-note" ? "trainer-note-v1" : d.action === "revoke-client" ? "client-revocation-v1" : invitation ? "invitation-v1" : d.action === "complete-onboarding" ? "onboarding-v1" : "profile-v1";
  request = invitation ? { ...request, token: d.acceptanceTokenDigest ?? null, callerUid: uid } : { ...request, callerUid: uid, policyVersion: 1 };
  return { d, key, kind, hashVersion, hash: stableHash(request), id: commandId(uid, key) };
}
function terminal(b: ReturnType<typeof resolutionBinding>, r: Data, uid: string) {
  let outcome = "applied", stamp = r.committedAt;
  if (r.schemaVersion === 2) {
    if (!exact(r, ["schemaVersion", "commandKind", "protocolVersion", "callerUid", "originalCommandKind", "requestHashVersion", "requestHash", "outcome", "decidedAt"]) ||
        r.commandKind !== "own-command-fence" || r.protocolVersion !== 1 || r.callerUid !== uid || r.originalCommandKind !== b.kind ||
        r.requestHashVersion !== b.hashVersion || r.requestHash !== b.hash || r.outcome !== "not-applied-fenced") fail();
    outcome = r.outcome; stamp = r.decidedAt;
  } else {
    const invite = b.kind.startsWith("invitation-");
    if (!exact(r, ["schemaVersion", "commandKind", "callerUid", "workspaceId", "requestHash", "result", "committedAt", ...(invite ? ["invitationId"] : ["client-membership-revoke","trainer-note-save"].includes(b.kind) ? ["clientUid"] : [])]) ||
        r.schemaVersion !== 1 || r.commandKind !== b.kind || r.callerUid !== uid || r.requestHash !== b.hash) fail();
    if (typeof r.workspaceId !== "string") fail();
    participantUid(strictPathIdentifier(r.workspaceId, "workspace"), "workspace");
    if (b.d.workspaceId !== undefined && r.workspaceId !== b.d.workspaceId) fail();
    const p = r.result;
    if (b.kind === "trainer-note-save") {
      if (r.clientUid !== b.d.noteSave.clientUid || !noteDecisionValid(p,r.workspaceId,b.d.noteSave)) fail();
      if (p.status === "note-conflict") outcome = "not-applied-conflict";
    } else if (b.kind === "client-membership-revoke") {
      if (r.clientUid !== b.d.revocation.clientUid || !revocationDecisionValid(p,r.workspaceId,b.d.revocation,b.key)) fail();
      if (p.status === "revocation-conflict") outcome = "not-applied-conflict";
    } else if (invite) {
      const id = b.d.action === "create" ? b.id : b.d.invitationId;
      const version = b.d.action === "create" ? 1 : b.d.expectedVersion + (b.d.action === "resend" ? 1 : 0);
      const status = b.d.action === "accept" ? "accepted" : b.d.action === "revoke" ? "revoked" : "pending";
      if (!exact(p, ["expiresAtMillis", "invitationId", "status", "tokenVersion", "workspaceId"]) || r.invitationId !== id ||
          p.invitationId !== id || p.workspaceId !== r.workspaceId || p.status !== status || p.tokenVersion !== version || !time(p.expiresAtMillis)) fail();
      if (["create", "resend"].includes(b.d.action) && (!(stamp instanceof Timestamp) || p.expiresAtMillis !== stamp.toMillis() + INVITATION_FIXTURE_POLICY.lifetimeMillis)) fail();
    } else if (b.kind === "onboarding-complete") {
      if (!exact(p, ["workspaceId", "status", "accountRevision", "onboardingRevision"]) || p.workspaceId !== r.workspaceId || p.status !== "complete" ||
          p.accountRevision !== b.d.onboarding.expectedAccountRevision + 1 || p.onboardingRevision !== b.d.onboarding.expectedOnboardingRevision + 1) fail();
    } else {
      if (!exact(p, ["workspaceId", "status", "accountRevision", "profileCommandKey"]) || p.workspaceId !== r.workspaceId || p.profileCommandKey !== b.key || !integer(p.accountRevision, 1) ||
          !["profile-updated", "profile-conflict"].includes(p.status) || (p.status === "profile-updated" ? p.accountRevision !== b.d.profileUpdate.expectedRevision + 1 : p.accountRevision <= b.d.profileUpdate.expectedRevision)) fail();
      if (p.status === "profile-conflict") outcome = "not-applied-conflict";
    }
  }
  if (!(stamp instanceof Timestamp) || !time(stamp.toMillis())) fail();
  return { protocolVersion: 1, commandKind: b.kind, commandId: b.id, requestHashVersion: b.hashVersion, requestHash: b.hash, outcome, decidedAtMillis: stamp.toMillis() };
}
export async function resolveOwnCommand(db: Firestore, caller: string | undefined, raw: unknown) {
  requireProvisioningEmulators();
  if (!caller) throw new Error("identity-required");
  const uid = participantUid(caller, "uid"), b = resolutionBinding(uid, raw);
  const user = await verifiedEmulatorIdentity(uid).catch(() => { throw new Error("identity-required"); });
  // Every transport attempt/replay is bounded, independently of terminalization admission.
  await db.runTransaction(async tx => {
    const ref = db.doc(`commandResolutionRequests/${stableHash(uid)}`), s = await tx.get(ref), minute = Math.floor(Date.now() / 60000);
    const count = s.get("minute") === minute ? s.get("count") : 0;
    if (!Number.isSafeInteger(count) || count < 0 || count >= 120) throw new Error("rate-limited");
    tx.set(ref, { minute, count: count + 1 });
  }, { maxAttempts: 3 });
  const started = Date.now();
  return db.runTransaction(async tx => {
    if (Date.now() - started >= 5000) fail();
    const ref = db.doc(`lifecycleCommands/${b.id}`);
    const [a, c] = await tx.getAll(db.doc(`users/${uid}`), ref);
    if (a!.get("schemaVersion") !== 1 || a!.get("accountStatus") !== "active" || (!Number.isSafeInteger(a!.get("lifecycleRevision")) || a!.get("lifecycleRevision") < 1) || a!.get("emailNormalized") !== user.email!.toLowerCase()) throw new Error("identity-required");
    if (c!.exists) return terminal(b, c!.data()!, uid);
    if (!b.key.startsWith("or1-")) fail();
    const config = (await requireCommandGeneration(tx, db, b.key))!;
    const maximum = config.get("maxFencesPerCaller");
    if (config.get("absenceEnabled") !== true || !integer(maximum, 1) || maximum > 100) fail();
    const budget = db.doc(`commandResolutionBudgets/${stableHash({ uid, generation: config.get("generation") })}`), old = await tx.get(budget);
    const count = old.exists ? old.get("count") : 0;
    if (!Number.isSafeInteger(count) || count < 0 || count >= maximum) throw new Error("rate-limited");
    const fence = { schemaVersion: 2, commandKind: "own-command-fence", protocolVersion: 1, callerUid: uid,
      originalCommandKind: b.kind, requestHashVersion: b.hashVersion, requestHash: b.hash, outcome: "not-applied-fenced", decidedAt: Timestamp.now() };
    if (Date.now() - started >= 5000) fail();
    tx.create(ref, fence); tx.set(budget, { count: count + 1 });
    return terminal(b, fence, uid);
  }, { maxAttempts: 3 });
}
