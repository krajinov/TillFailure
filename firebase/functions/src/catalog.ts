import { FieldValue, Firestore } from "firebase-admin/firestore";
import { commandId, stableHash } from "./hashing.js";

export type MembershipStatus = "active" | "revoked";

export interface MembershipTransition {
  readonly callerUid: string;
  readonly idempotencyKey: string;
  readonly workspaceId: string;
  readonly uid: string;
  readonly role: "trainer" | "client";
  readonly nextStatus: MembershipStatus;
  readonly expectedRevision: number;
  readonly maxMembershipsPerAccount: number;
}

export interface CatalogTransitionResult {
  readonly uid: string;
  readonly activeMembershipCount: number;
  readonly entitlementStatus: "active" | "inactive";
  readonly membershipRevision: number;
  readonly replayed: boolean;
}

export async function transitionMembership(db: Firestore, input: MembershipTransition): Promise<CatalogTransitionResult> {
  const requestHash = stableHash(input);
  const receiptRef = db.doc(`lifecycleCommands/${commandId(input.callerUid, input.idempotencyKey)}`);
  const accountRef = db.doc(`users/${input.uid}`);
  const entitlementRef = db.doc(`users/${input.uid}/authorizations/systemCatalog`);
  const workspaceRef = db.doc(`workspaces/${input.workspaceId}`);
  const membershipRef = db.doc(`workspaces/${input.workspaceId}/memberships/${input.uid}`);
  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(receiptRef, accountRef, entitlementRef, workspaceRef, membershipRef);
    const receipt = documents[0]!;
    const account = documents[1]!;
    const entitlement = documents[2]!;
    const workspace = documents[3]!;
    const membership = documents[4]!;
    if (receipt.exists) {
      if (receipt.get("requestHash") !== requestHash) throw new Error("idempotency-key-reused");
      return { ...(receipt.get("result") as Omit<CatalogTransitionResult, "replayed">), replayed: true };
    }
    if (!account.exists || !entitlement.exists || !workspace.exists) throw new Error("lifecycle-source-missing");
    const previousRevision = membership.exists ? membership.get("revision") as number : 0;
    if (previousRevision !== input.expectedRevision) throw new Error("stale-revision");
    const oldContributes = membership.exists && membership.get("catalogContributionActive") === true;
    const newContributes = input.nextStatus === "active" && workspace.get("status") === "active";
    const oldCount = entitlement.get("activeMembershipCount") as number;
    const nextCount = oldCount + Number(newContributes) - Number(oldContributes);
    if (!Number.isInteger(oldCount) || nextCount < 0 || nextCount > input.maxMembershipsPerAccount) throw new Error("membership-bound-violated");
    const entitlementStatus: "active" | "inactive" = account.get("accountStatus") === "active" && nextCount > 0 ? "active" : "inactive";
    const membershipRevision = previousRevision + 1;
    const result = { uid: input.uid, activeMembershipCount: nextCount, entitlementStatus, membershipRevision };
    transaction.set(membershipRef, {
      schemaVersion: 1,
      workspaceId: input.workspaceId,
      userId: input.uid,
      role: input.role,
      status: input.nextStatus,
      revision: membershipRevision,
      catalogContributionActive: newContributes
    });
    transaction.update(entitlementRef, {
      status: entitlementStatus,
      activeMembershipCount: nextCount,
      revision: FieldValue.increment(1)
    });
    transaction.update(workspaceRef, { membershipRevision: FieldValue.increment(1) });
    transaction.create(receiptRef, { schemaVersion: 1, requestHash, commandKind: "membership-transition", result, committedAt: FieldValue.serverTimestamp() });
    return { ...result, replayed: false };
  });
}

export interface WorkspaceTransition {
  readonly callerUid: string;
  readonly idempotencyKey: string;
  readonly workspaceId: string;
  readonly nextStatus: "active" | "suspended";
  readonly expectedMembershipRevision: number;
  readonly maxMembershipsPerAccount: number;
  readonly maxMembershipsPerWorkspace: number;
  readonly maxWrites: number;
}

export async function transitionWorkspace(db: Firestore, input: WorkspaceTransition): Promise<{ affected: number; replayed: boolean }> {
  const requestHash = stableHash(input);
  const receiptRef = db.doc(`lifecycleCommands/${commandId(input.callerUid, input.idempotencyKey)}`);
  const workspaceRef = db.doc(`workspaces/${input.workspaceId}`);
  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(receiptRef, workspaceRef);
    const receipt = documents[0]!;
    const workspace = documents[1]!;
    if (receipt.exists) {
      if (receipt.get("requestHash") !== requestHash) throw new Error("idempotency-key-reused");
      return { affected: receipt.get("affected") as number, replayed: true };
    }
    if (!workspace.exists || workspace.get("membershipRevision") !== input.expectedMembershipRevision) throw new Error("stale-revision");
    const memberships = await transaction.get(db.collection(`workspaces/${input.workspaceId}/memberships`).where("status", "==", "active"));
    if (memberships.size > input.maxMembershipsPerWorkspace) throw new Error("workspace-membership-bound-violated");
    const estimatedWrites = memberships.size * 2 + 2;
    if (estimatedWrites > input.maxWrites) throw new Error("write-budget-exceeded");
    const entitlementRefs = memberships.docs.map((membership) => db.doc(`users/${membership.id}/authorizations/systemCatalog`));
    const accountRefs = memberships.docs.map((membership) => db.doc(`users/${membership.id}`));
    const lifecycleDocuments = await transaction.getAll(...entitlementRefs, ...accountRefs);
    const lifecycleByPath = new Map(lifecycleDocuments.map((document) => [document.ref.path, document]));
    memberships.docs.forEach((membership, index) => {
      const oldContributes = membership.get("catalogContributionActive") === true;
      const newContributes = input.nextStatus === "active";
      const entitlement = lifecycleByPath.get(entitlementRefs[index]!.path);
      const account = lifecycleByPath.get(accountRefs[index]!.path);
      if (!entitlement?.exists || !account?.exists) {
        throw new Error(`lifecycle-source-missing:${entitlementRefs[index]!.path}:${Boolean(entitlement?.exists)}:${accountRefs[index]!.path}:${Boolean(account?.exists)}`);
      }
      const oldCount = entitlement.get("activeMembershipCount") as number;
      const nextCount = oldCount + Number(newContributes) - Number(oldContributes);
      if (!Number.isInteger(oldCount) || nextCount < 0 || nextCount > input.maxMembershipsPerAccount) {
        throw new Error("membership-bound-violated");
      }
      transaction.update(membership.ref, { catalogContributionActive: newContributes, revision: FieldValue.increment(1) });
      transaction.update(entitlement.ref, {
        activeMembershipCount: nextCount,
        status: account.get("accountStatus") === "active" && nextCount > 0 ? "active" : "inactive",
        revision: FieldValue.increment(1)
      });
    });
    transaction.update(workspaceRef, { status: input.nextStatus, membershipRevision: FieldValue.increment(1) });
    transaction.create(receiptRef, { schemaVersion: 1, requestHash, commandKind: "workspace-transition", affected: memberships.size, committedAt: FieldValue.serverTimestamp() });
    return { affected: memberships.size, replayed: false };
  });
}

export async function setAccountEnabled(db: Firestore, uid: string, enabled: boolean): Promise<void> {
  const accountRef = db.doc(`users/${uid}`);
  const entitlementRef = db.doc(`users/${uid}/authorizations/systemCatalog`);
  await db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(accountRef, entitlementRef);
    const account = documents[0]!;
    const entitlement = documents[1]!;
    if (!account.exists || !entitlement.exists) throw new Error("lifecycle-source-missing");
    const accountStatus = enabled ? "active" : "disabled";
    const count = entitlement.get("activeMembershipCount") as number;
    transaction.update(accountRef, { accountStatus, lifecycleRevision: FieldValue.increment(1) });
    transaction.update(entitlementRef, { status: enabled && count > 0 ? "active" : "inactive", revision: FieldValue.increment(1) });
  });
}
