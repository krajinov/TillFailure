import { verifiedEmulatorIdentity } from "./invitations.js";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { commandId, stableHash } from "./hashing.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { requireProvisioningEmulators } from "./provisioning.js";

// Local fixtures only. This is not approval of a release collection/retention policy.
export const ONBOARDING_FIXTURE_POLICY = Object.freeze({ version: 1, maxName: 80, maxLocale: 35, maxZone: 64, maxRevision: 2_147_483_646 });
function fail(reason: string): never { throw new Error(reason); }
const integer = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0 && (v as number) <= ONBOARDING_FIXTURE_POLICY.maxRevision;
export function validBasics(d: Record<string, unknown>): boolean {
  if (typeof d.displayName !== "string" || d.displayName.trim() !== d.displayName || d.displayName.length < 1 ||
      d.displayName.length > 80 || /[\p{Cc}\p{Cs}]/u.test(d.displayName) ||
      typeof d.locale !== "string" || d.locale.length > 35 || !/^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*$/.test(d.locale) ||
      typeof d.timeZoneId !== "string" || d.timeZoneId.length > 64 ||
      !/^(?:UTC|[A-Za-z_+-]+(?:\/[A-Za-z0-9_+-]+)+)$/.test(d.timeZoneId)) return false;
  try { new Intl.Locale(d.locale); new Intl.DateTimeFormat("en", { timeZone: d.timeZoneId }); return true; }
  catch { return false; }
}
export async function completeOnboarding(db: Firestore, callerUid: string | undefined, raw: unknown) {
  requireProvisioningEmulators();
  if (!callerUid) fail("unauthenticated");
  const uid = participantUid(callerUid, "uid");
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) fail("input-invalid");
  const d = raw as Record<string, unknown>;
  const keys = ["action", "workspaceId", "idempotencyKey", "onboarding"];
  if (Object.keys(d).length !== keys.length || Object.keys(d).some(k => !keys.includes(k)) ||
      d.action !== "complete-onboarding") fail("input-invalid");
  const wid = strictPathIdentifier(d.workspaceId, "workspaceId");
  const key = strictPathIdentifier(d.idempotencyKey, "idempotencyKey");
  const p = d.onboarding as Record<string, unknown>;
  const fields = ["displayName", "locale", "timeZoneId", "acknowledged", "expectedAccountRevision", "expectedOnboardingRevision"];
  if (!p || typeof p !== "object" || Array.isArray(p) || Object.keys(p).length !== fields.length ||
      Object.keys(p).some(k => !fields.includes(k)) || !validBasics(p) || p.acknowledged !== true ||
      !integer(p.expectedAccountRevision) || !integer(p.expectedOnboardingRevision) ||
      p.expectedAccountRevision >= ONBOARDING_FIXTURE_POLICY.maxRevision || p.expectedOnboardingRevision >= ONBOARDING_FIXTURE_POLICY.maxRevision) fail("input-invalid");
  // Re-read Admin Auth for every invocation, including receipt replay. Payloads never select UID.
  const user = await verifiedEmulatorIdentity(uid);
  const requestHash = stableHash({ ...d, callerUid: uid, policyVersion: 1 });
  return db.runTransaction(async tx => {
    const accountRef = db.doc(`users/${uid}`), profileRef = db.doc(`workspaces/${wid}/clientProfiles/${uid}`);
    const receiptRef = db.doc(`lifecycleCommands/${commandId(uid, key)}`);
    const [a, w, m, c, directory, receipt] = await tx.getAll(accountRef, db.doc(`workspaces/${wid}`),
      db.doc(`workspaces/${wid}/memberships/${uid}`), profileRef, db.doc(`users/${uid}/membershipRefs/${wid}`), receiptRef);
    if (a!.get("schemaVersion") !== 1 || a!.get("accountStatus") !== "active" ||
        a!.get("emailNormalized") !== user.email!.toLowerCase() || !integer(a!.get("lifecycleRevision")) ||
        w!.get("schemaVersion") !== 1 || w!.get("status") !== "active" || !integer(w!.get("membershipRevision")) ||
        !integer(w!.get("activeRosterCount")) || w!.get("activeRosterCount") < 1 || w!.get("activeRosterCount") > 99 ||
        w!.get("catalogContributionCount") !== w!.get("activeRosterCount") ||
        m!.get("schemaVersion") !== 1 || m!.get("workspaceId") !== wid || m!.get("userId") !== uid ||
        m!.get("role") !== "client" || m!.get("status") !== "active" || !integer(m!.get("revision")) ||
        m!.get("catalogContributionActive") !== true || directory!.get("schemaVersion") !== 1 || directory!.get("status") !== "active") fail("unauthorized");
    const profile = c!.data();
    const allowed = ["schemaVersion", "workspaceId", "userId", "trainerId", "createdAt", "onboardingStatus",
      "onboardingRevision", "onboardingPolicyVersion", "completedAt"];
    if (!profile || Object.keys(profile).some(k => !allowed.includes(k)) || profile.schemaVersion !== 1 ||
        profile.workspaceId !== wid || profile.userId !== uid || !(profile.createdAt instanceof Timestamp)) fail("record-invalid");
    participantUid(profile.trainerId, "trainerId");
    const revision = profile.onboardingRevision === undefined && profile.onboardingStatus === "pending" ? 0 : profile.onboardingRevision;
    if (!integer(revision) || !["pending", "complete"].includes(profile.onboardingStatus) ||
        profile.onboardingStatus === "pending" && (profile.completedAt !== undefined || profile.onboardingPolicyVersion !== undefined) ||
        profile.onboardingStatus === "complete" && (revision < 1 || profile.onboardingPolicyVersion !== 1 || !(profile.completedAt instanceof Timestamp))) fail("record-invalid");
    const account = a!.data()!;
    // PR 2/3 deliberately created an empty displayName placeholder, with no preference fields.
    const legacyEmpty = (account.displayName === undefined || account.displayName === "") &&
      account.locale === undefined && account.timeZoneId === undefined;
    const accountRevision = account.profileRevision === undefined && legacyEmpty ? 0 : account.profileRevision;
    if (!integer(accountRevision) || accountRevision > 0 && !validBasics(account) ||
        accountRevision === 0 && !legacyEmpty) fail("record-invalid");
    if (receipt!.exists) {
      const r = receipt!.data()!;
      if (r.schemaVersion !== 1 || r.commandKind !== "onboarding-complete" || r.callerUid !== uid ||
          r.workspaceId !== wid || r.requestHash !== requestHash) fail("idempotency-key-reused");
      const result = r.result;
      if (!(r.committedAt instanceof Timestamp) || !result ||
          Object.keys(result).sort().join(",") !== "accountRevision,onboardingRevision,status,workspaceId" ||
          result.status !== "complete" || result.workspaceId !== wid ||
          result.accountRevision !== (p.expectedAccountRevision as number) + 1 || result.onboardingRevision !== (p.expectedOnboardingRevision as number) + 1 ||
          profile.onboardingStatus !== "complete" || revision < result.onboardingRevision || accountRevision < result.accountRevision) fail("record-invalid");
      return { ...result, replayed: true };
    }
    if (profile.onboardingStatus === "complete") fail("already-complete");
    if (accountRevision !== p.expectedAccountRevision || revision !== p.expectedOnboardingRevision) fail("revision-conflict");
    const now = Timestamp.now();
    const result = { workspaceId: wid, status: "complete", accountRevision: accountRevision + 1, onboardingRevision: revision + 1 };
    // Update only the three global fields and their revision; never replace unrelated data.
    tx.update(accountRef, { displayName: p.displayName, locale: p.locale, timeZoneId: p.timeZoneId, profileRevision: result.accountRevision });
    tx.update(profileRef, { onboardingStatus: "complete", onboardingRevision: result.onboardingRevision, onboardingPolicyVersion: 1, completedAt: now });
    tx.create(receiptRef, { schemaVersion: 1, commandKind: "onboarding-complete", callerUid: uid, workspaceId: wid,
      requestHash, result, committedAt: now });
    return { ...result, replayed: false };
  });
}

export function onboardingFailure(error: unknown): { code: "unauthenticated" | "permission-denied" | "failed-precondition" | "internal"; reason: string } {
  const reason = error instanceof Error ? error.message : "";
  if (reason === "unauthenticated") return { code: "unauthenticated", reason };
  if (reason === "unverified-email") return { code: "permission-denied", reason };
  if (reason === "unauthorized") return { code: "permission-denied", reason };
  if (["input-invalid", "record-invalid", "idempotency-key-reused", "revision-conflict", "already-complete"].includes(reason))
    return { code: "failed-precondition", reason };
  return { code: "internal", reason: "unavailable" };
}
