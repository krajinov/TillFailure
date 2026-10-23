import { Firestore, Timestamp } from "firebase-admin/firestore";
import { verifiedEmulatorIdentity, INVITATION_FIXTURE_POLICY } from "./invitations.js";
import { requireProvisioningEmulators } from "./provisioning.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { projectMembershipCounters, contributionChange } from "./catalog.js";
import { requireCommandGeneration } from "./command-identity.js";
import { commandId, stableHash } from "./hashing.js";

type Data = Record<string, any>;
const fail = (reason = "record-invalid"): never => { throw new Error(reason); };
export const revocationRevision = (n: unknown): n is number => Number.isSafeInteger(n) && (n as number) >= 1 && (n as number) <= 2147483646;
const exact = (v: unknown, keys: string[]): v is Data => !!v && typeof v === "object" && !Array.isArray(v) && Object.keys(v).sort().join() === [...keys].sort().join();
export const REVOCATION_FIELDS = ["clientUid", "expectedTargetRevision", "expectedWorkspaceRevision", "expectedCallerMembershipRevision", "expectedCallerLifecycleRevision"];
export function validRevocationBinding(v: unknown): v is Data {
  if (!exact(v, REVOCATION_FIELDS) || !REVOCATION_FIELDS.slice(1).every(k => revocationRevision(v[k]) && v[k] < 2147483646)) return false;
  try { participantUid(v.clientUid, "clientUid"); return true; } catch { return false; }
}
function member(v: Data | undefined, wid: string, uid: string, role?: string) {
  if (!v || v.schemaVersion !== 1 || v.workspaceId !== wid || v.userId !== uid || !["trainer", "client"].includes(v.role) ||
      role && v.role !== role || !["active", "revoked"].includes(v.status) || !revocationRevision(v.revision) ||
      v.catalogContributionActive !== (v.status === "active")) fail();
}
function profile(v: Data | undefined, wid: string, uid: string, trainer: string) {
  if (!v || Object.keys(v).some(k=>!["schemaVersion","workspaceId","userId","trainerId","createdAt","onboardingStatus","onboardingRevision","onboardingPolicyVersion","completedAt"].includes(k)) || v.schemaVersion !== 1 || v.workspaceId !== wid || v.userId !== uid || v.trainerId !== trainer ||
      !(v.createdAt instanceof Timestamp) || v.createdAt.toMillis() < 0 || !["pending", "complete"].includes(v.onboardingStatus)) fail();
  if (v!.onboardingStatus === "pending" ? (v!.onboardingRevision !== undefined && v!.onboardingRevision !== 0 || v!.completedAt !== undefined || v!.onboardingPolicyVersion !== undefined) :
      (!revocationRevision(v!.onboardingRevision) || v!.onboardingPolicyVersion !== 1 || !(v!.completedAt instanceof Timestamp))) fail();
}
export function revocationDecisionValid(result: unknown, wid: string, binding: Data, key: string): boolean {
  if (!exact(result, ["workspaceId", "clientUid", "status", "commandKey", "targetMembershipRevision", "workspaceRevision", "callerMembershipRevision", "callerLifecycleRevision"]) ||
      result.workspaceId !== wid || result.clientUid !== binding.clientUid || result.commandKey !== key ||
      !["client-revoked", "revocation-conflict"].includes(result.status) ||
      !["targetMembershipRevision", "workspaceRevision", "callerMembershipRevision", "callerLifecycleRevision"].every(k => revocationRevision(result[k]))) return false;
  if (result.status === "client-revoked") return result.targetMembershipRevision === binding.expectedTargetRevision + 1 && result.workspaceRevision === binding.expectedWorkspaceRevision + 1 &&
      result.callerMembershipRevision === binding.expectedCallerMembershipRevision && result.callerLifecycleRevision === binding.expectedCallerLifecycleRevision;
  return result.targetMembershipRevision !== binding.expectedTargetRevision || result.workspaceRevision !== binding.expectedWorkspaceRevision ||
      result.callerMembershipRevision !== binding.expectedCallerMembershipRevision || result.callerLifecycleRevision !== binding.expectedCallerLifecycleRevision;
}

/** A single bounded transaction; preparation is read-only and confers no later authority. */
export async function clientRevocationOperation(db: Firestore, caller: string | undefined, raw: unknown, clock: () => number = Date.now) {
  requireProvisioningEmulators();
  if (!caller) fail("unauthenticated");
  const uid = participantUid(caller, "uid");
  const d = raw as Data, prepare = d?.action === "prepare-client-revocation";
  if (!exact(d, prepare ? ["action", "workspaceId", "clientUid"] : ["action", "workspaceId", "revocation", "idempotencyKey"]) ||
      !prepare && d.action !== "revoke-client") fail("input-invalid");
  const wid = participantUid(strictPathIdentifier(d.workspaceId, "workspaceId"), "workspaceId");
  if (!prepare && !validRevocationBinding(d.revocation)) fail("input-invalid");
  const target = participantUid(prepare ? d.clientUid : d.revocation.clientUid, "clientUid");
  if (target === uid) fail("unauthorized");
  const key = prepare ? undefined : participantUid(strictPathIdentifier(d.idempotencyKey, "key"), "key");
  const user = await verifiedEmulatorIdentity(uid), start = clock();
  const hash = key ? stableHash({ ...d, callerUid: uid, policyVersion: 1 }) : undefined;
  return db.runTransaction(async tx => {
    const deadline = () => { if (clock() - start >= 5000) fail("unavailable"); }; deadline();
    if (key) await requireCommandGeneration(tx, db, key);
    const paths = [`users/${uid}`, `workspaces/${wid}`, `workspaces/${wid}/memberships/${uid}`, `workspaces/${wid}/trainerProfiles/${uid}`,
      `users/${uid}/membershipRefs/${wid}`, `workspaces/${wid}/memberships/${target}`, `workspaces/${wid}/clientProfiles/${target}`,
      `users/${target}`, `users/${target}/authorizations/systemCatalog`, `users/${target}/membershipRefs/${wid}`];
    if (key) paths.push(`lifecycleCommands/${commandId(uid, key)}`);
    const docs = await tx.getAll(...paths.map(p => db.doc(p)));
    const [a,w,m,t,dir,c,p,account,ent,discovery,receipt] = docs;
    if (a!.get("schemaVersion") !== 1 || a!.get("accountStatus") !== "active" || !revocationRevision(a!.get("lifecycleRevision")) || a!.get("emailNormalized") !== user.email!.toLowerCase() ||
        w!.get("schemaVersion") !== 1 || w!.get("status") !== "active" || w!.get("ownerUid") !== uid || !revocationRevision(w!.get("membershipRevision"))) fail("unauthorized");
    member(m!.data(),wid,uid,"trainer");
    if (m!.get("status") !== "active" || t!.get("schemaVersion") !== 1 || t!.get("workspaceId") !== wid || t!.get("userId") !== uid || dir!.get("schemaVersion") !== 1 || dir!.get("status") !== "active") fail("unauthorized");
    member(c!.data(),wid,target,"client"); profile(p!.data(),wid,target,uid);
    if (receipt?.exists) {
      const r = receipt.data()!;
      if (!exact(r,["schemaVersion","commandKind","callerUid","workspaceId","clientUid","requestHash","result","committedAt"]) || r.schemaVersion !== 1 || r.commandKind !== "client-membership-revoke" ||
          r.callerUid !== uid || r.workspaceId !== wid || r.clientUid !== target || r.requestHash !== hash || !(r.committedAt instanceof Timestamp) ||
          !revocationDecisionValid(r.result,wid,d.revocation,key!)) fail("idempotency-key-reused");
      deadline(); return { revocationDecision: r.result, replayed: true };
    }
    if (account!.get("schemaVersion") !== 1 || !["active","disabled"].includes(account!.get("accountStatus")) || !revocationRevision(account!.get("lifecycleRevision")) ||
        ent!.get("schemaVersion") !== 1 || !["active","inactive"].includes(ent!.get("status")) || !revocationRevision(ent!.get("revision")) || ent!.get("revision") >= 2147483646) fail();
    const roster = await tx.get(db.collection(`workspaces/${wid}/memberships`).where("status","==","active").limit(6));
    if (roster.size > 5 || roster.size !== w!.get("activeRosterCount") || roster.size !== w!.get("catalogContributionCount")) fail("counter-inconsistent");
    roster.docs.forEach(s => member(s.data(),wid,s.id));
    const headers = await tx.get(db.collection(`users/${target}/workspaces/${wid}/assignedPrograms`).limit(1));
    const indexes = await tx.get(db.collection(`workspaces/${wid}/assignedPrograms`).where("clientId","==",target).limit(1));
    if (!headers.empty || !indexes.empty) fail("assignment-artifacts");
    // Validate source arithmetic even for a stale command: malformed sources cannot
    // become a durable negative receipt. Revoked targets contribute a zero delta.
    if (c!.get("status") === "active" ? discovery!.get("schemaVersion") !== 1 || discovery!.get("status") !== "active" : discovery!.exists) fail();
    const projected = projectMembershipCounters({accountCount:ent!.get("activeMembershipCount"),rosterCount:w!.get("activeRosterCount"),contributionCount:w!.get("catalogContributionCount"),
      wasActive:c!.get("status")==="active",becomesActive:false,oldContributes:c!.get("catalogContributionActive"),newContributes:false,maxMembershipsPerAccount:INVITATION_FIXTURE_POLICY.maxMembershipsPerAccount,maxMembershipsPerWorkspace:5});
    if (ent!.get("status") === "active" && (account!.get("accountStatus") !== "active" || ent!.get("activeMembershipCount") < 1)) fail("counter-inconsistent");
    const binding = { clientUid: target, expectedTargetRevision: c!.get("revision"), expectedWorkspaceRevision: w!.get("membershipRevision"),
      expectedCallerMembershipRevision: m!.get("revision"), expectedCallerLifecycleRevision: a!.get("lifecycleRevision") };
    const stale = !prepare && REVOCATION_FIELDS.some(k => d.revocation[k] !== binding[k as keyof typeof binding]);
    const result = { workspaceId:wid, clientUid:target, status: stale ? "revocation-conflict" : "client-revoked", commandKey:key,
      targetMembershipRevision:binding.expectedTargetRevision + (stale ? 0 : 1), workspaceRevision:binding.expectedWorkspaceRevision + (stale ? 0 : 1),
      callerMembershipRevision:binding.expectedCallerMembershipRevision, callerLifecycleRevision:binding.expectedCallerLifecycleRevision };
    const record = { schemaVersion:1, commandKind:"client-membership-revoke", callerUid:uid, workspaceId:wid, clientUid:target, requestHash:hash, result, committedAt:Timestamp.now() };
    deadline();
    if (stale) { tx.create(receipt!.ref,record); return {revocationDecision:result,replayed:false}; }
    if (c!.get("status") !== "active" || discovery!.get("schemaVersion") !== 1 || discovery!.get("status") !== "active" || !validRevocationBinding(binding)) fail();
    if (prepare) return { revocationPreparation:binding };
    const change = contributionChange(account!,ent!,true,false,projected.accountCount);
    tx.update(c!.ref,{status:"revoked",catalogContributionActive:false,revision:result.targetMembershipRevision});
    tx.delete(discovery!.ref);
    tx.update(ent!.ref,{activeMembershipCount:projected.accountCount,status:change.status,revision:ent!.get("revision")+1});
    tx.update(w!.ref,{membershipRevision:result.workspaceRevision,activeRosterCount:projected.rosterCount,catalogContributionCount:projected.contributionCount});
    tx.create(receipt!.ref,record);
    return {revocationDecision:result,replayed:false};
  },{maxAttempts:3});
}
