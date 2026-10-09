import { FieldValue, Firestore } from "firebase-admin/firestore";
import { projectMembershipCounters } from "./catalog.js";
import { SPIKE_PROJECT_ID } from "./environment.js";
import { commandId, stableHash } from "./hashing.js";
import { participantUid, strictPathIdentifier } from "./input-validation.js";

export interface TrainerProvisioningInput {
  readonly uid: string;
  readonly workspaceId: string;
  readonly workspaceName: string;
  readonly idempotencyKey: string;
  readonly expectedAccountRevision: number;
  readonly expectedEntitlementRevision: number;
  readonly expectedWorkspaceRevision: 0;
}

export interface VerifiedTrainerIdentity {
  readonly uid: string;
  readonly email: string;
  readonly emailVerified: boolean;
  readonly disabled: boolean;
}

export interface OperatorAuthority {
  /** Derived from a verified Auth token by the operator channel, never from command JSON. */
  readonly uid: string;
  /** Fixed emulator-channel allowlist, independent of the caller's identity or command JSON. */
  readonly allowedUid: string;
  readonly emailVerified: boolean;
  readonly disabled: boolean;
}

export interface ProvisioningCaps {
  readonly maxMembershipsPerAccount: number;
  readonly maxMembershipsPerWorkspace: number;
}

export interface TrainerProvisioningResult {
  readonly uid: string;
  readonly workspaceId: string;
  readonly membershipRevision: 1;
  readonly replayed: boolean;
}

export const PR2_EMULATOR_OPERATOR_UID = "pr2_operator";

const revision = (value: unknown, field: string): number => {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) throw new Error(`${field}-invalid`);
  return value;
};

const loopbackHost = /^(127\.0\.0\.1|localhost):([0-9]{1,5})$/;
function isLoopbackEndpoint(value: string | undefined): boolean {
  const match = value?.match(loopbackHost);
  return !!match && Number(match[2]) > 0 && Number(match[2]) <= 65535;
}

/** Reject every route to a real project or host before acquiring a provisioning service. */
export function requireProvisioningEmulators(): void {
  const projectIds = [process.env.GCLOUD_PROJECT, process.env.GOOGLE_CLOUD_PROJECT].filter(Boolean);
  if (projectIds.length === 0 || projectIds.some(id => id !== SPIKE_PROJECT_ID) ||
      !isLoopbackEndpoint(process.env.FIREBASE_AUTH_EMULATOR_HOST) ||
      !isLoopbackEndpoint(process.env.FIRESTORE_EMULATOR_HOST)) {
    throw new Error("pr2-named-local-emulators-required");
  }
}

/** Emulator-only trusted primitive. There is deliberately no callable or mobile bridge. */
export async function provisionTrainer(
  db: Firestore,
  input: TrainerProvisioningInput,
  trainer: VerifiedTrainerIdentity,
  operator: OperatorAuthority,
  caps: ProvisioningCaps
): Promise<TrainerProvisioningResult> {
  requireProvisioningEmulators();
  if (operator.uid !== PR2_EMULATOR_OPERATOR_UID || operator.allowedUid !== PR2_EMULATOR_OPERATOR_UID ||
      !operator.emailVerified || operator.disabled || operator.uid === input.uid) {
    throw new Error("operator-unauthorized");
  }
  const uid = participantUid(input.uid, "uid");
  const operatorUid = participantUid(operator.uid, "operatorUid");
  const workspaceId = strictPathIdentifier(input.workspaceId, "workspaceId");
  strictPathIdentifier(input.idempotencyKey, "idempotencyKey");
  if (trainer.uid !== uid || !trainer.emailVerified || trainer.disabled ||
      typeof trainer.email !== "string" || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trainer.email) || trainer.email.length > 254) {
    throw new Error("trainer-identity-ineligible");
  }
  if (typeof input.workspaceName !== "string" || input.workspaceName.trim() !== input.workspaceName ||
      input.workspaceName.length < 1 || input.workspaceName.length > 80) throw new Error("workspace-name-invalid");
  const expectedAccountRevision = revision(input.expectedAccountRevision, "expected-account-revision");
  const expectedEntitlementRevision = revision(input.expectedEntitlementRevision, "expected-entitlement-revision");
  if (input.expectedWorkspaceRevision !== 0) throw new Error("stale-workspace-revision");
  // Validate configured caps before creating any document reference or entering a transaction.
  const initial = projectMembershipCounters({
    accountCount: 0, rosterCount: 0, contributionCount: 0, wasActive: false, becomesActive: true,
    oldContributes: false, newContributes: true, ...caps
  });
  const emailNormalized = trainer.email.toLowerCase();
  const requestHash = stableHash({ input, trainerUid: trainer.uid, emailNormalized, caps });
  const receiptId = commandId(operatorUid, input.idempotencyKey);
  const receiptRef = db.doc(`lifecycleCommands/${receiptId}`);
  const auditRef = db.doc(`operatorAudit/${receiptId}`);
  const accountRef = db.doc(`users/${uid}`);
  const entitlementRef = db.doc(`users/${uid}/authorizations/systemCatalog`);
  const refsCollection = db.collection(`users/${uid}/membershipRefs`);
  const discoveryRef = refsCollection.doc(workspaceId);
  const workspaceRef = db.doc(`workspaces/${workspaceId}`);
  const membershipRef = workspaceRef.collection("memberships").doc(uid);
  const profileRef = workspaceRef.collection("trainerProfiles").doc(uid);
  return db.runTransaction(async transaction => {
    const documents = await transaction.getAll(
      receiptRef, auditRef, accountRef, entitlementRef, workspaceRef, membershipRef, profileRef, discoveryRef
    );
    const receipt = documents[0]!;
    const audit = documents[1]!;
    const account = documents[2]!;
    const entitlement = documents[3]!;
    const workspace = documents[4]!;
    const membership = documents[5]!;
    const profile = documents[6]!;
    const discovery = documents[7]!;
    if (receipt.exists) {
      if (receipt.get("schemaVersion") !== 1 || receipt.get("commandKind") !== "trainer-provision" ||
          receipt.get("callerUid") !== operatorUid || receipt.get("uid") !== uid ||
          receipt.get("workspaceId") !== workspaceId || receipt.get("requestHash") !== requestHash) {
        throw new Error("idempotency-key-reused");
      }
      const result = receipt.get("result") as Partial<TrainerProvisioningResult> | undefined;
      if (!audit.exists || audit.get("schemaVersion") !== 1 || audit.get("action") !== "trainer-provision" ||
          audit.get("operatorUid") !== operatorUid || audit.get("targetUid") !== uid ||
          audit.get("workspaceId") !== workspaceId || audit.get("requestHash") !== requestHash ||
          audit.get("receiptId") !== receiptId || !result || result.uid !== uid || result.workspaceId !== workspaceId ||
          result.membershipRevision !== 1 || "replayed" in result) throw new Error("receipt-inconsistent");
      return { uid, workspaceId, membershipRevision: 1, replayed: true };
    }
    if (audit.exists || workspace.exists || membership.exists || profile.exists || discovery.exists) throw new Error("conflicting-ownership");
    const existingRefs = await transaction.get(refsCollection.limit(1));
    if (!existingRefs.empty) throw new Error("trainer-already-member");
    if (account.exists) {
      if (account.get("schemaVersion") !== 1 || account.get("accountStatus") !== "active" ||
          account.get("emailNormalized") !== emailNormalized) throw new Error("account-conflict-or-schema");
      if (revision(account.get("lifecycleRevision"), "account-revision") !== expectedAccountRevision) throw new Error("stale-revision");
    } else if (expectedAccountRevision !== 0) throw new Error("stale-revision");
    if (entitlement.exists) {
      if (!account.exists || entitlement.get("schemaVersion") !== 1 || entitlement.get("status") !== "inactive" ||
          entitlement.get("activeMembershipCount") !== 0) throw new Error("entitlement-conflict-or-schema");
      if (revision(entitlement.get("revision"), "entitlement-revision") !== expectedEntitlementRevision) throw new Error("stale-revision");
    } else if (account.exists || expectedEntitlementRevision !== 0) throw new Error("entitlement-missing-or-stale");

    const result = { uid, workspaceId, membershipRevision: 1 as const };
    if (!account.exists) transaction.create(accountRef, {
      schemaVersion: 1, accountStatus: "active", lifecycleRevision: 1, emailNormalized,
      displayName: "", createdAt: FieldValue.serverTimestamp(), createdBy: operator.uid
    });
    const entitlementData = { schemaVersion: 1, status: "active", activeMembershipCount: initial.accountCount,
      revision: entitlement.exists ? expectedEntitlementRevision + 1 : 1, updatedAt: FieldValue.serverTimestamp(), updatedBy: operator.uid };
    if (entitlement.exists) transaction.update(entitlementRef, entitlementData);
    else transaction.create(entitlementRef, entitlementData);
    transaction.create(workspaceRef, {
      schemaVersion: 1, name: input.workspaceName, ownerUid: uid, status: "active", membershipRevision: 1,
      activeRosterCount: initial.rosterCount, catalogContributionCount: initial.contributionCount,
      createdAt: FieldValue.serverTimestamp(), createdBy: operator.uid
    });
    transaction.create(membershipRef, {
      schemaVersion: 1, workspaceId, userId: uid, role: "trainer", status: "active", revision: 1,
      catalogContributionActive: true, joinedAt: FieldValue.serverTimestamp()
    });
    transaction.create(discoveryRef, { schemaVersion: 1, status: "active" });
    transaction.create(profileRef, { schemaVersion: 1, workspaceId, userId: uid, bio: "", createdAt: FieldValue.serverTimestamp() });
    transaction.create(receiptRef, { schemaVersion: 1, commandKind: "trainer-provision", callerUid: operator.uid,
      uid, workspaceId, requestHash, result, committedAt: FieldValue.serverTimestamp() });
    transaction.create(auditRef, { schemaVersion: 1, action: "trainer-provision", operatorUid: operator.uid,
      targetUid: uid, workspaceId, requestHash, receiptId, committedAt: FieldValue.serverTimestamp() });
    return { ...result, replayed: false };
  });
}
