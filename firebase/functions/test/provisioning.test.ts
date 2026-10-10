import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, describe, it } from "node:test";
import { getAuth } from "firebase-admin/auth";
import { projectMembershipCounters } from "../src/catalog.js";
import { emulatorFirestore } from "../src/environment.js";
import { commandId } from "../src/hashing.js";
import { OperatorAuthority, provisionTrainer, requireProvisioningEmulators, TrainerProvisioningInput, VerifiedTrainerIdentity } from "../src/provisioning.js";

const db = emulatorFirestore();
const operator: OperatorAuthority = { uid: "pr2_operator", allowedUid: "pr2_operator", emailVerified: true, disabled: false };
const trainer: VerifiedTrainerIdentity = { uid: "pr2_trainer", email: "trainer@example.invalid", emailVerified: true, disabled: false };
const caps = { maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 };
const input = (key = "first", workspaceId = "first_workspace"): TrainerProvisioningInput => ({
  uid: trainer.uid, workspaceId, workspaceName: "First Workspace", idempotencyKey: key,
  expectedAccountRevision: 0, expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0
});

describe("operator-only first workspace", () => {
  beforeEach(async () => {
    for (const name of ["users", "workspaces", "lifecycleCommands", "operatorAudit"]) {
      await db.recursiveDelete(db.collection(name));
    }
  });

  it("commits exactly one owner, membership, entitlement, directory, profile, receipt and audit; replays without writes", async () => {
    const command = input();
    const first = await provisionTrainer(db, command, trainer, operator, caps);
    assert.deepEqual(first, { uid: trainer.uid, workspaceId: command.workspaceId, membershipRevision: 1, replayed: false });
    const paths = [
      `users/${trainer.uid}`, `users/${trainer.uid}/authorizations/systemCatalog`,
      `users/${trainer.uid}/membershipRefs/${command.workspaceId}`, `workspaces/${command.workspaceId}`,
      `workspaces/${command.workspaceId}/memberships/${trainer.uid}`,
      `workspaces/${command.workspaceId}/trainerProfiles/${trainer.uid}`,
      `lifecycleCommands/${commandId(operator.uid, command.idempotencyKey)}`,
      `operatorAudit/${commandId(operator.uid, command.idempotencyKey)}`
    ];
    const before = await db.getAll(...paths.map(path => db.doc(path)));
    assert.ok(before.every(document => document.exists));
    assert.equal(before[1]?.get("activeMembershipCount"), 1);
    assert.equal(before[1]?.get("status"), "active");
    assert.equal(before[3]?.get("ownerUid"), trainer.uid);
    assert.equal(before[3]?.get("activeRosterCount"), 1);
    assert.equal(before[3]?.get("catalogContributionCount"), 1);
    assert.equal(before[4]?.get("role"), "trainer");
    assert.equal(before[7]?.get("operatorUid"), operator.uid);
    assert.equal((await provisionTrainer(db, command, trainer, operator, caps)).replayed, true);
    const after = await db.getAll(...paths.map(path => db.doc(path)));
    assert.deepEqual(after.map(doc => doc.updateTime?.toMillis()), before.map(doc => doc.updateTime?.toMillis()));
    await assert.rejects(provisionTrainer(db, { ...command, workspaceName: "Changed" }, trainer, operator, caps), /idempotency-key-reused/);
  });

  it("rejects inconsistent or nonlocal emulator configuration before a Firestore write", async () => {
    const original = {
      GCLOUD_PROJECT: process.env.GCLOUD_PROJECT,
      GOOGLE_CLOUD_PROJECT: process.env.GOOGLE_CLOUD_PROJECT,
      FIREBASE_AUTH_EMULATOR_HOST: process.env.FIREBASE_AUTH_EMULATOR_HOST,
      FIRESTORE_EMULATOR_HOST: process.env.FIRESTORE_EMULATOR_HOST
    };
    try {
      for (const override of [
        { GCLOUD_PROJECT: undefined, GOOGLE_CLOUD_PROJECT: undefined },
        { GCLOUD_PROJECT: "production-project" },
        { GOOGLE_CLOUD_PROJECT: "production-project" },
        { FIREBASE_AUTH_EMULATOR_HOST: undefined },
        { FIRESTORE_EMULATOR_HOST: "firestore.example.com:8180" },
        { FIRESTORE_EMULATOR_HOST: "localhost:99999" }
      ]) {
        for (const [key, value] of Object.entries({ ...original, ...override })) {
          if (value === undefined) delete process.env[key];
          else process.env[key] = value;
        }
        assert.throws(requireProvisioningEmulators, /pr2-named-local-emulators-required/);
        await assert.rejects(provisionTrainer(db, input(), trainer, operator, caps), /pr2-named-local-emulators-required/);
      }
    } finally {
      for (const [key, value] of Object.entries(original)) {
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      }
    }
    assert.equal((await db.collection("workspaces").get()).size, 0);
    assert.equal((await db.collection("operatorAudit").get()).size, 0);
  });

  it("rejects inconsistent audit and receipt data without changing provisioned state", async () => {
    const command = input();
    await provisionTrainer(db, command, trainer, operator, caps);
    const receiptId = commandId(operator.uid, command.idempotencyKey);
    const auditRef = db.doc(`operatorAudit/${receiptId}`);
    const receiptRef = db.doc(`lifecycleCommands/${receiptId}`);
    const accountRef = db.doc(`users/${trainer.uid}`);
    const workspaceRef = db.doc(`workspaces/${command.workspaceId}`);
    const before = await db.getAll(accountRef, workspaceRef);
    await auditRef.update({ operatorUid: "another_operator" });
    await assert.rejects(provisionTrainer(db, command, trainer, operator, caps), /receipt-inconsistent/);
    await auditRef.update({ operatorUid: operator.uid });
    await receiptRef.update({ result: { uid: trainer.uid, workspaceId: "wrong_workspace", membershipRevision: 1 } });
    await assert.rejects(provisionTrainer(db, command, trainer, operator, caps), /receipt-inconsistent/);
    const after = await db.getAll(accountRef, workspaceRef);
    assert.deepEqual(after.map(doc => doc.updateTime?.toMillis()), before.map(doc => doc.updateTime?.toMillis()));
  });

  it("does not reset a pre-existing trainer account or entitlement when seeding", async () => {
    const accountRef = db.doc("users/pr2_trainer");
    const entitlementRef = db.doc("users/pr2_trainer/authorizations/systemCatalog");
    await accountRef.set({ schemaVersion: 1, accountStatus: "suspended", lifecycleRevision: 7, emailNormalized: "other@example.invalid" });
    await entitlementRef.set({ schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 9 });
    const before = await db.getAll(accountRef, entitlementRef);
    const run = spawnSync(process.execPath, ["scripts/seed-pr2.mjs"], {
      cwd: process.cwd(), encoding: "utf8", env: { ...process.env, TF_PR2_TEST_PASSWORD: "localTestPassword!" }
    });
    assert.notEqual(run.status, 0);
    assert.match(run.stderr, /fixture already exists|Auth fixture/);
    const after = await db.getAll(accountRef, entitlementRef);
    assert.deepEqual(after.map(doc => doc.data()), before.map(doc => doc.data()));
    assert.deepEqual(after.map(doc => doc.updateTime?.toMillis()), before.map(doc => doc.updateTime?.toMillis()));
    assert.equal((await db.collection("workspaces").get()).size, 0);
  });

  it("allows a schema-current empty account only at its exact revision", async () => {
    await db.doc(`users/${trainer.uid}`).set({ schemaVersion: 1, accountStatus: "active", lifecycleRevision: 3, emailNormalized: trainer.email });
    await db.doc(`users/${trainer.uid}/authorizations/systemCatalog`).set({ schemaVersion: 1, status: "inactive", activeMembershipCount: 0, revision: 2 });
    await assert.rejects(provisionTrainer(db, input(), trainer, operator, caps), /stale-revision/);
    const result = await provisionTrainer(db, { ...input(), expectedAccountRevision: 3, expectedEntitlementRevision: 2 }, trainer, operator, caps);
    assert.equal(result.replayed, false);
    assert.equal((await db.doc(`users/${trainer.uid}/authorizations/systemCatalog`).get()).get("revision"), 3);
    assert.equal((await db.doc(`users/${trainer.uid}`).get()).get("lifecycleRevision"), 3);
  });

  it("denies unauthorized operators, self-provisioning and ineligible or malformed Auth identities before writes", async () => {
    for (const badOperator of [
      { ...operator, uid: "ordinary_user" }, { ...operator, uid: "ordinary_user", allowedUid: "ordinary_user" },
      { ...operator, emailVerified: false },
      { ...operator, disabled: true }, { ...operator, uid: trainer.uid, allowedUid: trainer.uid }
    ]) await assert.rejects(provisionTrainer(db, input(), trainer, badOperator, caps), /operator-unauthorized/);
    for (const badTrainer of [{ ...trainer, emailVerified: false }, { ...trainer, disabled: true }, { ...trainer, uid: "other" }]) {
      await assert.rejects(provisionTrainer(db, input(), badTrainer, operator, caps), /trainer-identity-ineligible/);
    }
    await assert.rejects(provisionTrainer(db, { ...input(), uid: "bad/uid" }, trainer, operator, caps), /uid/);
    await assert.rejects(provisionTrainer(db, { ...input(), uid: "bad\ud800" }, trainer, operator, caps), /uid/);
    await assert.rejects(provisionTrainer(db, { ...input(), workspaceId: "bad/path" }, trainer, operator, caps), /workspaceId/);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0);
    assert.equal((await db.collection("workspaces").get()).size, 0);
  });

  it("pins CLI authority to the fixture and commits or replays only its verified command", async () => {
    await getAuth().createUser({
      uid: "pr2_ordinary_operator", email: "pr2-ordinary@example.invalid",
      password: "localTestPassword!", emailVerified: true
    });
    const run = spawnSync(process.execPath, ["lib/src/provisioning-cli.js", "/nonexistent-input.json"], {
      cwd: process.cwd(), encoding: "utf8", env: {
        ...process.env, TF_PR2_OPERATOR_EMAIL: "pr2-ordinary@example.invalid",
        TF_PR2_OPERATOR_PASSWORD: "localTestPassword!",
        TF_PR2_EMULATOR_OPERATOR_UID: "pr2_ordinary_operator"
      }
    });
    assert.notEqual(run.status, 0);
    assert.match(run.stderr, /operator-unauthorized/);
    assert.equal((await db.collection("workspaces").get()).size, 0);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0);
    assert.equal((await db.collection("operatorAudit").get()).size, 0);

    await getAuth().createUser({
      uid: "pr2_operator", email: "pr2-operator@example.invalid",
      password: "localTestPassword!", emailVerified: true
    });
    await getAuth().createUser({
      uid: "pr2_trainer", email: "pr2-trainer@example.invalid",
      password: "localTestPassword!", emailVerified: true
    });
    const directory = mkdtempSync(join(tmpdir(), "tf-pr2-cli-"));
    try {
      const file = join(directory, "command.json");
      writeFileSync(file, JSON.stringify({
        uid: "pr2_trainer", workspaceId: "cli_workspace", workspaceName: "CLI Workspace",
        idempotencyKey: "cli-first", expectedAccountRevision: 0,
        expectedEntitlementRevision: 0, expectedWorkspaceRevision: 0
      }));
      const runAuthorized = () => spawnSync(process.execPath, ["lib/src/provisioning-cli.js", file], {
        cwd: process.cwd(), encoding: "utf8", env: {
          ...process.env, TF_PR2_OPERATOR_EMAIL: "pr2-operator@example.invalid",
          TF_PR2_OPERATOR_PASSWORD: "localTestPassword!"
        }
      });
      const committed = runAuthorized();
      assert.equal(committed.status, 0, committed.stderr);
      assert.deepEqual(JSON.parse(committed.stdout), {
        uid: "pr2_trainer", workspaceId: "cli_workspace", membershipRevision: 1, replayed: false
      });
      const replayed = runAuthorized();
      assert.equal(replayed.status, 0, replayed.stderr);
      assert.equal(JSON.parse(replayed.stdout).replayed, true);
      assert.equal((await db.collection("workspaces").get()).size, 1);
      assert.equal((await db.collection("lifecycleCommands").get()).size, 1);
      assert.equal((await db.collection("operatorAudit").get()).size, 1);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it("rejects schema, ownership, stale revision and cap conflicts without partial writes", async () => {
    await db.doc(`users/${trainer.uid}`).set({ schemaVersion: 2, accountStatus: "active", lifecycleRevision: 1, emailNormalized: trainer.email });
    await assert.rejects(provisionTrainer(db, { ...input(), expectedAccountRevision: 1 }, trainer, operator, caps), /account-conflict-or-schema/);
    await db.doc(`users/${trainer.uid}`).delete();
    await db.doc("workspaces/first_workspace").set({ schemaVersion: 1, ownerUid: "other" });
    await assert.rejects(provisionTrainer(db, input(), trainer, operator, caps), /conflicting-ownership/);
    await db.doc("workspaces/first_workspace").delete();
    await assert.rejects(provisionTrainer(db, input(), trainer, operator, { ...caps, maxMembershipsPerAccount: 0 }), /invalid-maximum-memberships/);
    await assert.rejects(provisionTrainer(db, input(), trainer, operator, { ...caps, maxMembershipsPerWorkspace: 100 }), /invalid-maximum-workspace-memberships/);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 0);
    assert.equal((await db.collection("operatorAudit").get()).size, 0);
    assert.equal((await db.doc(`users/${trainer.uid}/authorizations/systemCatalog`).get()).exists, false);
  });

  it("rejects a deleted workspace parent with another user's orphaned membership or profile", async () => {
    for (const collection of ["memberships", "trainerProfiles", "clientProfiles"] as const) {
      const workspaceId = `orphan_${collection}`;
      const workspaceRef = db.doc(`workspaces/${workspaceId}`);
      const orphanRef = workspaceRef.collection(collection).doc("former_member");
      await orphanRef.set({
        schemaVersion: 1, workspaceId, userId: "former_member", role: "client", status: "active"
      });
      const before = await orphanRef.get();
      assert.equal((await workspaceRef.get()).exists, false);
      const command = input(`orphan_${collection}`, workspaceId);
      await assert.rejects(provisionTrainer(db, command, trainer, operator, caps), /workspace-orphaned-data/);
      const after = await orphanRef.get();
      assert.deepEqual(after.data(), before.data());
      assert.equal(after.updateTime?.toMillis(), before.updateTime?.toMillis());
      assert.equal((await workspaceRef.get()).exists, false);
      assert.equal((await db.doc(`users/${trainer.uid}`).get()).exists, false);
      assert.equal((await db.doc(`users/${trainer.uid}/authorizations/systemCatalog`).get()).exists, false);
      assert.equal((await db.doc(`lifecycleCommands/${commandId(operator.uid, command.idempotencyKey)}`).get()).exists, false);
      assert.equal((await db.doc(`operatorAudit/${commandId(operator.uid, command.idempotencyKey)}`).get()).exists, false);
      assert.equal((await workspaceRef.collection("memberships").doc(trainer.uid).get()).exists, false);
    }
  });

  it("serializes concurrent first-workspace commands and uses the ordinary lifecycle bounds", async () => {
    const attempts = await Promise.allSettled([
      provisionTrainer(db, input("one", "workspace_one"), trainer, operator, caps),
      provisionTrainer(db, input("two", "workspace_two"), trainer, operator, caps)
    ]);
    assert.equal(attempts.filter(item => item.status === "fulfilled").length, 1);
    assert.equal((await db.collection("workspaces").get()).size, 1);
    assert.equal((await db.collection("lifecycleCommands").get()).size, 1);
    assert.equal((await db.collection("operatorAudit").get()).size, 1);
    assert.equal((await db.doc(`users/${trainer.uid}/authorizations/systemCatalog`).get()).get("activeMembershipCount"), 1);
    assert.throws(() => projectMembershipCounters({ accountCount: 1, rosterCount: 0, contributionCount: 0,
      wasActive: false, becomesActive: true, oldContributes: false, newContributes: true,
      maxMembershipsPerAccount: 1, maxMembershipsPerWorkspace: 5 }), /membership-bound-violated/);
    assert.throws(() => projectMembershipCounters({ accountCount: 0, rosterCount: 5, contributionCount: 0,
      wasActive: false, becomesActive: true, oldContributes: false, newContributes: false,
      maxMembershipsPerAccount: 2, maxMembershipsPerWorkspace: 5 }), /workspace-roster-bound-violated/);
  });
});
