import { FieldPath, Firestore, Timestamp } from "firebase-admin/firestore";
import { verifiedEmulatorIdentity } from "./invitations.js";
import { requireProvisioningEmulators } from "./provisioning.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";
import { requireCommandGeneration } from "./command-identity.js";
import { commandId, stableHash } from "./hashing.js";
import { EXERCISE_AUTHORITY, exerciseAction, exerciseBytes, exerciseDecisionValid, exerciseDescription, exerciseExact, exerciseId, exerciseRevision, validExerciseDescriptor } from "./custom-exercise-contract.js";

type Data = Record<string, any>;
const fail = (s = "exercise-unavailable"): never => { throw new Error(s); };
const stamp = (v: unknown): v is Timestamp => v instanceof Timestamp && v.toMillis() >= 0 && v.toMillis() <= 8_640_000_000_000_000;
function definition(v: Data | undefined, wid: string, id: string, uid: string): Data {
  if (!exerciseExact(v, ["schemaVersion", "policyVersion", "workspaceId", "exerciseId", "ownerUid", "name", "instructions", "muscleGroups", "equipment", "revision", "createdAt", "updatedAt"]) ||
      v.schemaVersion !== 1 || v.policyVersion !== 1 || v.workspaceId !== wid || v.exerciseId !== id || !exerciseId(id) || v.ownerUid !== uid || !exerciseDescription(v) ||
      !exerciseRevision(v.revision) || !stamp(v.createdAt) || !stamp(v.updatedAt) || v.updatedAt.toMillis() < v.createdAt.toMillis()) fail();
  const projected: Data = { ...v!, createdAtMillis: v!.createdAt.toMillis(), updatedAtMillis: v!.updatedAt.toMillis() };
  delete projected.createdAt; delete projected.updatedAt;
  if (exerciseBytes(projected) > 16384) fail();
  return projected;
}
export async function customExerciseOperation(db: Firestore, caller: string | undefined, raw: unknown, clock: () => number = Date.now, beforeWrites?: () => Promise<void>) {
  requireProvisioningEmulators(); if (!caller) fail("unauthorized"); const uid = participantUid(caller, "uid"), user = await verifiedEmulatorIdentity(uid);
  await db.runTransaction(async tx => {
    const ref = db.doc(`customExerciseRequests/${stableHash(uid)}`), s = await tx.get(ref), minute = Math.floor(clock() / 60000), count = s.get("minute") === minute ? s.get("count") : 0;
    if (s.exists && (!exerciseExact(s.data(), ["minute", "count"]) || !Number.isSafeInteger(s.get("minute")) || s.get("minute") < 0 || !Number.isSafeInteger(s.get("count")) || s.get("count") < 0 || s.get("count") > 120)) fail("rate-limited");
    if (!Number.isSafeInteger(count) || count < 0 || count >= 120) fail("rate-limited");
    tx.set(ref, { minute, count: count + 1 });
  }, { maxAttempts: 3 });
  const d = raw as Data, mutation = exerciseAction(d?.action);
  const fields = mutation ? ["action", "workspaceId", "idempotencyKey", "exerciseWrite"] : d?.action === "exercise-prepare" ? ["action", "workspaceId"] :
    d?.action === "exercise-list" ? ["action", "workspaceId", "afterId"] : d?.action === "exercise-get" ? ["action", "workspaceId", "exerciseId"] : [];
  if (!fields.length || !exerciseExact(d, fields) || exerciseBytes({ data: d }) > 32768 || mutation && !validExerciseDescriptor(d) ||
      d.action === "exercise-list" && d.afterId !== null && !exerciseId(d.afterId) || d.action === "exercise-get" && !exerciseId(d.exerciseId)) fail();
  const wid = participantUid(strictPathIdentifier(d.workspaceId, "workspace"), "workspace");
  const started = clock();
  return db.runTransaction(async tx => {
    const deadline = () => { if (clock() - started >= 5000) fail(); }; deadline();
    if (mutation) await requireCommandGeneration(tx, db, d.idempotencyKey);
    const docs = await tx.getAll(...[`users/${uid}`, `workspaces/${wid}`, `workspaces/${wid}/memberships/${uid}`, `workspaces/${wid}/trainerProfiles/${uid}`, `users/${uid}/membershipRefs/${wid}`].map(p => db.doc(p)));
    const [a, w, m, t, dir] = docs;
    if (a!.get("schemaVersion") !== 1 || a!.get("accountStatus") !== "active" || !exerciseRevision(a!.get("lifecycleRevision")) || a!.get("emailNormalized") !== user.email!.toLowerCase() ||
        w!.get("schemaVersion") !== 1 || w!.get("status") !== "active" || !exerciseRevision(w!.get("membershipRevision")) ||
        m!.get("schemaVersion") !== 1 || m!.get("workspaceId") !== wid || m!.get("userId") !== uid || m!.get("role") !== "trainer" || m!.get("status") !== "active" || m!.get("catalogContributionActive") !== true || !exerciseRevision(m!.get("revision")) ||
        t!.get("schemaVersion") !== 1 || t!.get("workspaceId") !== wid || t!.get("userId") !== uid || dir!.get("schemaVersion") !== 1 || dir!.get("status") !== "active") fail("unauthorized");
    const authority = { authorLifecycleRevision: a!.get("lifecycleRevision"), authorMembershipRevision: m!.get("revision"), workspaceMembershipRevision: w!.get("membershipRevision") };
    const collection = db.collection(`workspaces/${wid}/exercises`);
    let result: Data;
    if (d.action === "exercise-prepare") result = { workspaceId: wid, authority };
    else if (d.action === "exercise-list") {
      let query = collection.where("ownerUid", "==", uid).orderBy(FieldPath.documentId()).limit(21);
      if (d.afterId !== null) query = query.startAfter(d.afterId);
      const values = (await tx.get(query)).docs.map(s => definition(s.data(), wid, s.id, uid));
      result = { workspaceId: wid, authority, items: values.slice(0, 20).map(v => ({ exerciseId: v.exerciseId, name: v.name, muscleGroups: v.muscleGroups, equipment: v.equipment, revision: v.revision })), nextId: values.length > 20 ? values[19]!.exerciseId : null };
    } else if (d.action === "exercise-get") result = { workspaceId: wid, authority, exercise: definition((await tx.get(collection.doc(d.exerciseId))).data(), wid, d.exerciseId, uid) };
    else {
      const p = d.exerciseWrite;
      if (!EXERCISE_AUTHORITY.every(k => p.authority[k] === (authority as Data)[k])) fail("unauthorized");
      const target = collection.doc(p.exerciseId), receipt = db.doc(`lifecycleCommands/${commandId(uid, d.idempotencyKey)}`), [old, r] = await tx.getAll(target, receipt);
      const kind = d.action === "create-custom-exercise" ? "custom-exercise-create" : "custom-exercise-correct", hash = stableHash({ ...d, callerUid: uid, policyVersion: 1 });
      if (r!.exists) {
        const v = r!.data()!;
        if (!exerciseExact(v, ["schemaVersion", "commandKind", "callerUid", "workspaceId", "exerciseId", "requestHash", "result", "committedAt"]) || v.schemaVersion !== 1 || v.commandKind !== kind || v.callerUid !== uid || v.workspaceId !== wid || v.exerciseId !== p.exerciseId || v.requestHash !== hash || !stamp(v.committedAt) || !exerciseDecisionValid(v.result, wid, p, d.action)) fail("idempotency-key-reused");
        deadline(); return { ...v.result, replayed: true };
      }
      const prior = old!.exists ? definition(old!.data(), wid, p.exerciseId, uid) : null;
      if (d.action === "correct-custom-exercise" && (!prior || prior.revision < p.expectedRevision)) fail();
      const revision = prior?.revision ?? 0, conflict = revision !== p.expectedRevision;
      if (!conflict && revision >= 2147483646) fail();
      const decision = { status: conflict ? "exercise-conflict" : d.action === "create-custom-exercise" ? "exercise-created" : "exercise-corrected", workspaceId: wid, exerciseId: p.exerciseId, exerciseRevision: conflict ? revision : revision + 1 };
      const now = Timestamp.now();
      if (!conflict && prior && now.toMillis() < prior.updatedAtMillis) fail();
      const next = { schemaVersion: 1, policyVersion: 1, workspaceId: wid, exerciseId: p.exerciseId, ownerUid: uid, name: p.name, instructions: p.instructions, muscleGroups: p.muscleGroups, equipment: p.equipment, revision: decision.exerciseRevision, createdAt: old!.get("createdAt") ?? now, updatedAt: now };
      if (!conflict) definition(next, wid, p.exerciseId, uid);
      if (beforeWrites) await beforeWrites(); deadline();
      if (!conflict) { if (prior) tx.set(target, next); else tx.create(target, next); }
      tx.create(receipt, { schemaVersion: 1, commandKind: kind, callerUid: uid, workspaceId: wid, exerciseId: p.exerciseId, requestHash: hash, result: decision, committedAt: now });
      result = { ...decision, replayed: false };
    }
    deadline();
    const cap = d.action === "exercise-list" ? 65536 : d.action === "exercise-get" ? 24576 : 4096;
    if (exerciseBytes(result) > cap) fail();
    return result;
  }, { maxAttempts: 3 });
}
