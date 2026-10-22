import { requireCommandGeneration } from "./command-identity.js";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { verifiedEmulatorIdentity } from "./invitations.js";
import { validBasics, ONBOARDING_FIXTURE_POLICY } from "./onboarding.js";
import { requireProvisioningEmulators } from "./provisioning.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { commandId, stableHash } from "./hashing.js";

const max = ONBOARDING_FIXTURE_POLICY.maxRevision;
const integer = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0 && (v as number) <= max;
function fail(reason: string): never { throw new Error(reason); }
function exact(d: unknown, keys: string[]): d is Record<string, unknown> {
  return !!d && typeof d === "object" && !Array.isArray(d) && Object.keys(d).sort().join(",") === keys.sort().join(",");
}
/** Own global basics only; no profile copy, fan-out, collection enumeration or mobile Rules grant. */
export async function basicProfileOperation(db: Firestore, callerUid: string | undefined, raw: unknown) {
  requireProvisioningEmulators();
  if (!callerUid) fail("unauthenticated");
  const uid = participantUid(callerUid, "uid");
  const read = (raw as Record<string, unknown> | null)?.action === "profile-get";
  if (!exact(raw, read ? ["action", "workspaceId"] : ["action", "workspaceId", "idempotencyKey", "profileUpdate"])) fail("input-invalid");
  const d = raw as Record<string, unknown>;
  if (!read && d.action !== "update-profile") fail("input-invalid");
  const wid = strictPathIdentifier(d.workspaceId, "workspaceId");
  const key = read ? undefined : strictPathIdentifier(d.idempotencyKey, "idempotencyKey");
  const p = d.profileUpdate as Record<string, unknown>;
  if (!read && (!exact(p, ["displayName", "locale", "timeZoneId", "expectedRevision"]) || !validBasics(p) ||
      !integer(p.expectedRevision) || p.expectedRevision < 1 || p.expectedRevision >= max)) fail("input-invalid");
  const user = await verifiedEmulatorIdentity(uid);
  const requestHash = read ? undefined : stableHash({ ...d, callerUid: uid, policyVersion: 1 });
  return db.runTransaction(async tx => {
    if (key) await requireCommandGeneration(tx, db, key);
    const accountRef = db.doc(`users/${uid}`);
    const receiptRef = key ? db.doc(`lifecycleCommands/${commandId(uid, key)}`) : undefined;
    const [a, w, m, c, directory] = await tx.getAll(accountRef, db.doc(`workspaces/${wid}`),
      db.doc(`workspaces/${wid}/memberships/${uid}`), db.doc(`workspaces/${wid}/clientProfiles/${uid}`),
      db.doc(`users/${uid}/membershipRefs/${wid}`));
    if (a!.get("schemaVersion") !== 1 || a!.get("accountStatus") !== "active" ||
        a!.get("emailNormalized") !== user.email!.toLowerCase() || !integer(a!.get("lifecycleRevision")) ||
        w!.get("schemaVersion") !== 1 || w!.get("status") !== "active" || !integer(w!.get("membershipRevision")) ||
        !integer(w!.get("activeRosterCount")) || w!.get("activeRosterCount") < 1 || w!.get("activeRosterCount") > 99 ||
        w!.get("catalogContributionCount") !== w!.get("activeRosterCount") ||
        m!.get("schemaVersion") !== 1 || m!.get("workspaceId") !== wid || m!.get("userId") !== uid ||
        m!.get("role") !== "client" || m!.get("status") !== "active" || !integer(m!.get("revision")) ||
        m!.get("catalogContributionActive") !== true || directory!.get("schemaVersion") !== 1 || directory!.get("status") !== "active") fail("unauthorized");
    const profile = c!.data();
    const allowed = ["schemaVersion", "workspaceId", "userId", "trainerId", "createdAt", "onboardingStatus", "onboardingRevision", "onboardingPolicyVersion", "completedAt"];
    if (!profile || Object.keys(profile).some(k => !allowed.includes(k)) || profile.schemaVersion !== 1 || profile.workspaceId !== wid ||
        profile.userId !== uid || !(profile.createdAt instanceof Timestamp) || profile.onboardingStatus !== "complete" ||
        !integer(profile.onboardingRevision) || profile.onboardingRevision < 1 || profile.onboardingPolicyVersion !== 1 ||
        !(profile.completedAt instanceof Timestamp)) fail("record-invalid");
    participantUid(profile.trainerId, "trainerId");
    const account = a!.data()!, revision = account.profileRevision;
    if (!integer(revision) || revision < 1 || !validBasics(account)) fail("record-invalid");
    if (read) return { workspaceId: wid, status: "profile", profile: { displayName: account.displayName, locale: account.locale,
      timeZoneId: account.timeZoneId, revision } };
    const receipt = await tx.get(receiptRef!);
    if (receipt.exists) {
      const r = receipt.data()!, result = r.result;
      if (r.schemaVersion !== 1 || r.commandKind !== "profile-update" || r.callerUid !== uid || r.workspaceId !== wid ||
          r.requestHash !== requestHash) fail("idempotency-key-reused");
      if (!(r.committedAt instanceof Timestamp) || !exact(result, ["workspaceId", "status", "accountRevision", "profileCommandKey"]) ||
          result.workspaceId !== wid || result.profileCommandKey !== key || !integer(result.accountRevision) || result.accountRevision > revision ||
          (result.status !== "profile-updated" && result.status !== "profile-conflict") ||
          (result.status === "profile-updated" ? result.accountRevision !== (p.expectedRevision as number) + 1 : result.accountRevision <= (p.expectedRevision as number))) fail("record-invalid");
      return { ...result, replayed: true };
    }
    // A durable negative receipt conclusively rejects this exact command, including a lost reply.
    // Only this validated conflict can settle recovery; transport/Auth errors cannot discard work.
    if (revision < (p.expectedRevision as number)) fail("record-invalid");
    const conflict = revision !== p.expectedRevision;
    const result = { workspaceId: wid, status: conflict ? "profile-conflict" : "profile-updated",
      accountRevision: conflict ? revision : revision + 1, profileCommandKey: key };
    if (!conflict) tx.update(accountRef, { displayName: p.displayName, locale: p.locale, timeZoneId: p.timeZoneId, profileRevision: result.accountRevision });
    tx.create(receiptRef!, { schemaVersion: 1, commandKind: "profile-update", callerUid: uid, workspaceId: wid,
      requestHash, result, committedAt: Timestamp.now() });
    return { ...result, replayed: false };
  }, { maxAttempts: 3 });
}
