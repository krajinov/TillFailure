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
  readonly expectedWorkspaceRevision: number;
  readonly maxMembershipsPerAccount: number;
  readonly maxMembershipsPerWorkspace: number;
}

export interface CatalogTransitionResult {
  readonly uid: string;
  readonly activeMembershipCount: number;
  readonly entitlementStatus: "active" | "inactive";
  readonly membershipRevision: number;
  readonly replayed: boolean;
}

// The Firestore Rules entitlement check accepts an integer count in 1..20; trusted commands must
// never emit an entitlement state those Rules cannot authorize, so the configured account cap is
// validated against the same documented ceiling. The workspace roster ceiling is derived from the
// documented transaction write budget ((200 - 2) / 2 membership/entitlement pairs).
export const RULES_ENTITLEMENT_COUNT_MAX = 20;
export const WORKSPACE_ROSTER_COUNT_MAX = 99;

interface CounterBounds {
  readonly max: number;
  readonly malformed: string;
  readonly outOfRange: string;
}

// One strict integer/bound validator for every lifecycle counter so membership, workspace, and
// account transitions cannot diverge: stored and computed values must be actual integers inside
// the configured bound. Strings, decimals, booleans, nulls, arrays, and objects are rejected
// instead of being coerced.
function strictCounter(value: unknown, bounds: CounterBounds): number {
  if (typeof value !== "number" || !Number.isInteger(value)) throw new Error(bounds.malformed);
  if (value < 0 || value > bounds.max) throw new Error(bounds.outOfRange);
  return value;
}

// Configured caps are trusted input, but they are validated as positive bounded integers so an
// unvalidated ceiling can never authorize a state the Rules or the write budget cannot support.
function strictConfiguredBound(value: unknown, max: number, failure: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1 || value > max) throw new Error(failure);
  return value;
}

// Every lifecycle command is receipt-backed and caller-bound: the receipt identity is derived from
// the caller and the idempotency key, so both must be present before any transaction work.
function requireCommandIdentity(input: { readonly callerUid: string; readonly idempotencyKey: string }): void {
  if (typeof input.callerUid !== "string" || input.callerUid.length === 0) throw new Error("caller-required");
  if (typeof input.idempotencyKey !== "string" || input.idempotencyKey.length === 0) throw new Error("idempotency-key-required");
}

// The current Firestore Rules authorize catalog access only for `schemaVersion == 1` account and
// entitlement documents (`activeAccount` and `catalogEntitled`). A trusted command must never
// write or report an authorization state those Rules deny, and an unknown version is rejected
// rather than silently migrated.
const CURRENT_SCHEMA_VERSION = 1;

function requireCurrentSchema(snapshot: FirebaseFirestore.DocumentSnapshot, failure: string): void {
  if (snapshot.get("schemaVersion") !== CURRENT_SCHEMA_VERSION) throw new Error(failure);
}

// Applied whenever a command is about to report catalog access as active: the account and its
// entitlement must both be on the current schema. Deactivation paths deliberately skip this check
// so access can always be revoked, even for records whose schema is unknown or damaged.
function requireAuthorizableSchema(account: FirebaseFirestore.DocumentSnapshot, entitlement: FirebaseFirestore.DocumentSnapshot): void {
  requireCurrentSchema(account, "account-schema-unsupported");
  requireCurrentSchema(entitlement, "entitlement-schema-unsupported");
}

// Every stored membership document is validated against one contract before its contribution flag,
// lifecycle state, or revision is used to derive a delta, so the workspace scan and the direct
// membership command cannot diverge. Each caller passes the statuses it may legitimately meet
// instead of the check being widened for everyone. The membership document ID is the account path
// segment used for `users/{uid}`, so the stored `userId` must equal it and the stored `workspaceId`
// must equal the workspace being handled; otherwise a malformed document could redirect an
// entitlement delta into another account just by occupying a plausible path. An unknown schema
// version, a role outside the documented `trainer`/`client` set, a non-boolean contribution flag, a
// status outside the documented set, and an invalid revision fail closed instead of being silently
// repaired, so no path-only authorization inference and no cross-account entitlement contamination
// is possible. Failures include the offending path so the record can be reconciled without guessing.
function requireValidMembership(
  membership: FirebaseFirestore.DocumentSnapshot,
  workspaceId: string,
  allowedStatuses: readonly string[]
): void {
  if (membership.get("schemaVersion") !== CURRENT_SCHEMA_VERSION) {
    throw new Error(`membership-schema-unsupported:${membership.ref.path}`);
  }
  if (membership.get("workspaceId") !== workspaceId) {
    throw new Error(`membership-workspace-mismatch:${membership.ref.path}`);
  }
  if (membership.get("userId") !== membership.id) {
    throw new Error(`membership-user-mismatch:${membership.ref.path}`);
  }
  if (membership.get("role") !== "trainer" && membership.get("role") !== "client") {
    throw new Error(`membership-role-invalid:${membership.ref.path}`);
  }
  if (typeof membership.get("catalogContributionActive") !== "boolean") {
    throw new Error(`membership-contribution-malformed:${membership.ref.path}`);
  }
  if (!allowedStatuses.includes(membership.get("status"))) {
    throw new Error(`membership-status-invalid:${membership.ref.path}`);
  }
  const revision = membership.get("revision");
  if (typeof revision !== "number" || !Number.isInteger(revision) || revision < 0) {
    throw new Error(`membership-revision-malformed:${membership.ref.path}`);
  }
}

// The bounded active-roster scan feeds both the counter consistency checks and the derivation of
// every affected account/entitlement reference, and it only ever transitions active relationships;
// keeping the allowed set self-contained means a future change to the scan filter cannot silently
// widen which lifecycles are transitioned.
const SCANNED_MEMBERSHIP_STATUSES: readonly string[] = ["active"];
// A direct membership command must also accept a valid inactive (revoked) document it is restoring.
// Only the documented membership lifecycle states are accepted, so an unknown status fails closed.
const DIRECT_MEMBERSHIP_STATUSES: readonly string[] = ["active", "revoked"];

function requireValidScannedMembership(membership: FirebaseFirestore.QueryDocumentSnapshot, workspaceId: string): void {
  requireValidMembership(membership, workspaceId, SCANNED_MEMBERSHIP_STATUSES);
}

// One classification of a membership contribution change, shared by the membership command and the
// workspace scan so the two paths cannot diverge. Only an addition (a new contribution or a
// restoration) increases access; a removal is a deactivation. A removal may keep a positive
// remaining count, but it must never elevate access: it preserves an already-active entitlement or
// deactivates it, and it never rewrites the stored schema, so a damaged record stays exactly as
// damaged — and exactly as unreadable under Rules — as it was.
interface ContributionChange {
  readonly adds: boolean;
  readonly removes: boolean;
  readonly entitlementActive: boolean;
  readonly storedEntitlementActive: boolean;
  readonly status: "active" | "inactive";
}

function contributionChange(
  account: FirebaseFirestore.DocumentSnapshot,
  entitlement: FirebaseFirestore.DocumentSnapshot,
  oldContributes: boolean,
  newContributes: boolean,
  nextCount: number
): ContributionChange {
  const adds = newContributes && !oldContributes;
  const removes = oldContributes && !newContributes;
  const storedEntitlementActive = entitlement.get("status") === "active";
  const wouldBeActive = account.get("accountStatus") === "active" && nextCount > 0;
  const status: "active" | "inactive" = wouldBeActive && (!removes || storedEntitlementActive) ? "active" : "inactive";
  return { adds, removes, entitlementActive: status === "active", storedEntitlementActive, status };
}

// Adding a contribution, or reporting active access the stored entitlement did not already have,
// requires the schema the Rules authorize. A pure removal is a deactivation and therefore stays
// possible while the account or entitlement schema is damaged: Rules already deny those reads
// (they require `schemaVersion == 1`), the command only lowers access, and nothing is repaired,
// activated, or broadened. A positive remaining count on a damaged record is safe to carry for the
// same reason — it is not readable until a separate trusted reconciliation repairs the schema.
function requireSchemaForContributionChange(
  account: FirebaseFirestore.DocumentSnapshot,
  entitlement: FirebaseFirestore.DocumentSnapshot,
  change: ContributionChange
): void {
  if (change.adds || (change.entitlementActive && !change.storedEntitlementActive)) {
    requireAuthorizableSchema(account, entitlement);
  }
}

export async function transitionMembership(db: Firestore, input: MembershipTransition): Promise<CatalogTransitionResult> {
  requireCommandIdentity(input);
  strictConfiguredBound(input.maxMembershipsPerAccount, RULES_ENTITLEMENT_COUNT_MAX, "invalid-maximum-memberships");
  strictConfiguredBound(input.maxMembershipsPerWorkspace, WORKSPACE_ROSTER_COUNT_MAX, "invalid-maximum-workspace-memberships");
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
    // An existing membership is validated before its contribution flag or lifecycle state derives a
    // delta and before its revision is compared: a missing or non-boolean contribution flag would
    // otherwise be read as "not contributing", so re-activating a damaged active membership would
    // increment both counters and overwrite the document, publishing catalog access instead of
    // failing closed. A valid revoked membership is still accepted so revocation and restoration
    // keep working, and a malformed document is never repaired by this command.
    if (membership.exists) requireValidMembership(membership, input.workspaceId, DIRECT_MEMBERSHIP_STATUSES);
    const previousRevision = membership.exists ? membership.get("revision") as number : 0;
    if (previousRevision !== input.expectedRevision) throw new Error("stale-revision");
    if (workspace.get("membershipRevision") !== input.expectedWorkspaceRevision) throw new Error("stale-workspace-revision");
    // Relationship state and catalog contribution are distinct concepts:
    // an active membership is part of the workspace roster even while the workspace is
    // suspended, but it contributes to catalog entitlement only in an active workspace.
    const wasActive = membership.exists && membership.get("status") === "active";
    const becomesActive = input.nextStatus === "active";
    const oldContributes = membership.exists && membership.get("catalogContributionActive") === true;
    const newContributes = becomesActive && workspace.get("status") === "active";
    // Account entitlement stays contribution-based.
    const accountCountBounds: CounterBounds = { max: input.maxMembershipsPerAccount, malformed: "membership-bound-violated", outOfRange: "membership-bound-violated" };
    const oldCount = strictCounter(entitlement.get("activeMembershipCount"), accountCountBounds);
    const nextCount = strictCounter(oldCount + Number(newContributes) - Number(oldContributes), accountCountBounds);
    // Workspace roster: every active relationship, in an active or suspended workspace.
    const rosterBounds: CounterBounds = { max: input.maxMembershipsPerWorkspace, malformed: "workspace-roster-malformed", outOfRange: "workspace-roster-bound-violated" };
    const rosterCount = strictCounter(workspace.get("activeRosterCount"), rosterBounds);
    const nextRosterCount = strictCounter(rosterCount + Number(becomesActive) - Number(wasActive), rosterBounds);
    // Workspace catalog contributions: a subset of the roster, zero while suspended.
    const contributionBounds: CounterBounds = { max: rosterCount, malformed: "workspace-contribution-malformed", outOfRange: "workspace-contribution-bound-violated" };
    const contributionCount = strictCounter(workspace.get("catalogContributionCount"), contributionBounds);
    const nextContributionCount = strictCounter(contributionCount + Number(newContributes) - Number(oldContributes), { ...contributionBounds, max: nextRosterCount });
    const entitlementChange = contributionChange(account, entitlement, oldContributes, newContributes, nextCount);
    // Adding a contribution or publishing access the stored entitlement did not already have needs
    // the schema the Rules authorize; revoking one of several contributions must still succeed while
    // the account or entitlement schema is damaged, because that is a deactivation and Rules deny
    // the damaged record's reads regardless of its remaining count.
    requireSchemaForContributionChange(account, entitlement, entitlementChange);
    const entitlementStatus = entitlementChange.status;
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
    transaction.update(workspaceRef, { membershipRevision: FieldValue.increment(1), activeRosterCount: nextRosterCount, catalogContributionCount: nextContributionCount });
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
  requireCommandIdentity(input);
  strictConfiguredBound(input.maxMembershipsPerAccount, RULES_ENTITLEMENT_COUNT_MAX, "invalid-maximum-memberships");
  strictConfiguredBound(input.maxMembershipsPerWorkspace, WORKSPACE_ROSTER_COUNT_MAX, "invalid-maximum-workspace-memberships");
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
    const rosterBounds: CounterBounds = { max: input.maxMembershipsPerWorkspace, malformed: "workspace-roster-malformed", outOfRange: "workspace-roster-bound-violated" };
    const rosterCount = strictCounter(workspace.get("activeRosterCount"), rosterBounds);
    const contributionCount = strictCounter(workspace.get("catalogContributionCount"), { ...rosterBounds, malformed: "workspace-contribution-malformed", outOfRange: "workspace-contribution-bound-violated", max: rosterCount });
    const memberships = await transaction.get(db.collection(`workspaces/${input.workspaceId}/memberships`).where("status", "==", "active"));
    if (memberships.size > input.maxMembershipsPerWorkspace) throw new Error("workspace-roster-bound-violated");
    // Validate every scanned membership's identity, schema, role, contribution flag, and lifecycle
    // state before the consistency math and before any account/entitlement reference is derived
    // from a membership path or any delta is applied. The same shared scan guards suspension and
    // restoration, and a single malformed member rejects the whole transition atomically: no write
    // (workspace status/revision, memberships, entitlements, receipt) commits on failure.
    memberships.docs.forEach((membership) => requireValidScannedMembership(membership, input.workspaceId));
    // The bounded active-roster scan is authoritative: an already oversized or drifted
    // roster/contribution state fails closed instead of silently transitioning.
    if (memberships.size !== rosterCount) throw new Error("workspace-roster-inconsistent");
    const scannedContributions = memberships.docs.filter((membership) => membership.get("catalogContributionActive") === true).length;
    if (scannedContributions !== contributionCount) throw new Error("workspace-contribution-inconsistent");
    const estimatedWrites = memberships.size * 2 + 2;
    if (estimatedWrites > input.maxWrites) throw new Error("write-budget-exceeded");
    const entitlementRefs = memberships.docs.map((membership) => db.doc(`users/${membership.id}/authorizations/systemCatalog`));
    const accountRefs = memberships.docs.map((membership) => db.doc(`users/${membership.id}`));
    // An empty roster is a valid state: nothing to read or adjust, and the transaction must
    // still be able to suspend/restore the workspace with zero affected memberships.
    const lifecycleDocuments = memberships.size === 0 ? [] : await transaction.getAll(...entitlementRefs, ...accountRefs);
    const lifecycleByPath = new Map(lifecycleDocuments.map((document) => [document.ref.path, document]));
    memberships.docs.forEach((membership, index) => {
      const oldContributes = membership.get("catalogContributionActive") === true;
      const newContributes = input.nextStatus === "active";
      const entitlement = lifecycleByPath.get(entitlementRefs[index]!.path);
      const account = lifecycleByPath.get(accountRefs[index]!.path);
      if (!entitlement?.exists || !account?.exists) {
        throw new Error(`lifecycle-source-missing:${entitlementRefs[index]!.path}:${Boolean(entitlement?.exists)}:${accountRefs[index]!.path}:${Boolean(account?.exists)}`);
      }
      const accountCountBounds: CounterBounds = { max: input.maxMembershipsPerAccount, malformed: "membership-bound-violated", outOfRange: "membership-bound-violated" };
      const oldCount = strictCounter(entitlement.get("activeMembershipCount"), accountCountBounds);
      const nextCount = strictCounter(oldCount + Number(newContributes) - Number(oldContributes), accountCountBounds);
      const entitlementChange = contributionChange(account, entitlement, oldContributes, newContributes, nextCount);
      // Restoring a workspace adds contributions and needs the schema the Rules authorize; suspending
      // one workspace while the member keeps a contribution elsewhere is a deactivation and must
      // still succeed for a schema-damaged record, whose reads Rules keep denying.
      requireSchemaForContributionChange(account, entitlement, entitlementChange);
      transaction.update(membership.ref, { catalogContributionActive: newContributes, revision: FieldValue.increment(1) });
      transaction.update(entitlement.ref, {
        activeMembershipCount: nextCount,
        status: entitlementChange.status,
        revision: FieldValue.increment(1)
      });
    });
    transaction.update(workspaceRef, {
      status: input.nextStatus,
      membershipRevision: FieldValue.increment(1),
      // Suspension disables contributions without removing active relationships; the
      // roster count is preserved from the authoritative bounded scan either way.
      activeRosterCount: memberships.size,
      catalogContributionCount: input.nextStatus === "active" ? memberships.size : 0
    });
    transaction.create(receiptRef, { schemaVersion: 1, requestHash, commandKind: "workspace-transition", affected: memberships.size, committedAt: FieldValue.serverTimestamp() });
    return { affected: memberships.size, replayed: false };
  });
}

export type AccountLifecycleStatus = "active" | "disabled";

export interface AccountLifecycleTransition {
  readonly callerUid: string;
  readonly idempotencyKey: string;
  readonly uid: string;
  readonly enabled: boolean;
  readonly expectedLifecycleRevision: number;
  readonly maxMembershipsPerAccount: number;
}

export interface AccountLifecycleResult {
  readonly uid: string;
  readonly accountStatus: AccountLifecycleStatus;
  readonly entitlementStatus: "active" | "inactive";
  readonly lifecycleRevision: number;
  readonly replayed: boolean;
}

// Revision-checked, receipt-backed account enable/disable. The previous unconditional update was
// last-writer-wins, so a delayed or retried enable could reactivate an account and its catalog
// entitlement after a newer disable had committed. Every command now names the expected lifecycle
// revision, is bound to its caller and idempotency key, and commits account state, entitlement
// state, the lifecycle revision, and the receipt in one transaction.
export async function transitionAccountLifecycle(db: Firestore, input: AccountLifecycleTransition): Promise<AccountLifecycleResult> {
  requireCommandIdentity(input);
  if (typeof input.uid !== "string" || input.uid.length === 0) throw new Error("account-uid-required");
  if (!Number.isInteger(input.expectedLifecycleRevision) || input.expectedLifecycleRevision < 0) throw new Error("invalid-expected-revision");
  strictConfiguredBound(input.maxMembershipsPerAccount, RULES_ENTITLEMENT_COUNT_MAX, "invalid-maximum-memberships");
  const requestHash = stableHash(input);
  const receiptRef = db.doc(`lifecycleCommands/${commandId(input.callerUid, input.idempotencyKey)}`);
  const accountRef = db.doc(`users/${input.uid}`);
  const entitlementRef = db.doc(`users/${input.uid}/authorizations/systemCatalog`);
  return db.runTransaction(async (transaction) => {
    const documents = await transaction.getAll(receiptRef, accountRef, entitlementRef);
    const receipt = documents[0]!;
    const account = documents[1]!;
    const entitlement = documents[2]!;
    if (receipt.exists) {
      if (receipt.get("requestHash") !== requestHash) throw new Error("idempotency-key-reused");
      return { ...(receipt.get("result") as Omit<AccountLifecycleResult, "replayed">), replayed: true };
    }
    if (!account.exists || !entitlement.exists) throw new Error("lifecycle-source-missing");
    const storedRevision = account.get("lifecycleRevision");
    if (!Number.isInteger(storedRevision)) throw new Error("account-revision-malformed");
    if (storedRevision !== input.expectedLifecycleRevision) throw new Error("stale-revision");
    // Enabling requires the schema versions the current Rules authorize on both documents, so a
    // missing, malformed, or unsupported version fails closed instead of reporting restored access
    // that Rules would still deny. Unknown data is never silently migrated here, and disable
    // deliberately skips this check so revocation stays possible for damaged records.
    if (input.enabled) requireAuthorizableSchema(account, entitlement);
    const storedCount = entitlement.get("activeMembershipCount");
    // Security-first disable: catalog authorization always becomes inactive, and the stored count
    // is preserved verbatim (never coerced, clamped, or repaired) for investigation/restoration.
    // Enabling instead requires a strictly valid integer count inside the configured cap, so a
    // malformed or above-cap source can never mark the account and entitlement active while the
    // Rules still deny every catalog read; that state needs a separate trusted reconciliation.
    const entitlementStatus: "active" | "inactive" = input.enabled
      ? strictCounter(storedCount, { max: input.maxMembershipsPerAccount, malformed: "entitlement-count-malformed", outOfRange: "membership-bound-violated" }) > 0
        ? "active"
        : "inactive"
      : "inactive";
    const accountStatus: AccountLifecycleStatus = input.enabled ? "active" : "disabled";
    const lifecycleRevision = (storedRevision as number) + 1;
    transaction.update(accountRef, { accountStatus, lifecycleRevision });
    transaction.update(entitlementRef, { status: entitlementStatus, revision: FieldValue.increment(1) });
    const result: Omit<AccountLifecycleResult, "replayed"> = { uid: input.uid, accountStatus, entitlementStatus, lifecycleRevision };
    transaction.create(receiptRef, {
      schemaVersion: 1,
      commandKind: "account-lifecycle",
      commandVersion: 1,
      callerUid: input.callerUid,
      uid: input.uid,
      enabled: input.enabled,
      expectedLifecycleRevision: input.expectedLifecycleRevision,
      storedEntitlementCount: storedCount === undefined ? null : storedCount,
      requestHash,
      result,
      committedAt: FieldValue.serverTimestamp()
    });
    return { ...result, replayed: false };
  });
}
