# PR 2 local trainer operator procedure

This procedure is **emulator-only**. The production operator identity, eligibility evidence, channel ownership, and membership caps have not been approved. No provisioning callable is exported. Mobile builds have no operator credential, provisioning request, or role-selection action.

1. Start the named `demo-tillfailure-m3` Auth and Firestore emulators on loopback. Set `GCLOUD_PROJECT=demo-tillfailure-m3`, `FIREBASE_AUTH_EMULATOR_HOST` and `FIRESTORE_EMULATOR_HOST` to their loopback addresses. Never point this procedure at production.
2. For a fresh test project, set `TF_PR2_TEST_PASSWORD` locally and run `npm --prefix firebase/functions run seed:pr2`. This create-only fixture command creates a verified Auth operator and a verified, empty trainer account. It creates **no workspace**. The operator UID is `pr2_operator`; the pending trainer UID is `pr2_trainer`. It refuses existing fixture Auth users, account, entitlement, or workspace state and never resets them. If interrupted after Auth creation, use a fresh emulator project; do not repair the fixture by rerunning the seeder against reused state.
3. Review the trainer's Auth UID, verified email, disabled flag, existing account and entitlement revisions, and proposed workspace ID/name. Record the eligibility decision outside the app. Put only the command JSON below in a local input file; do not commit the file or any credential.

```json
{
  "uid": "pr2_trainer",
  "workspaceId": "pr2_workspace",
  "workspaceName": "Training Workspace",
  "idempotencyKey": "operator-approved-unique-command-id",
  "expectedAccountRevision": 1,
  "expectedEntitlementRevision": 1,
  "expectedWorkspaceRevision": 0
}
```

4. Set `TF_PR2_OPERATOR_EMAIL=pr2-operator@example.invalid` and `TF_PR2_OPERATOR_PASSWORD` in the operator's local process environment. The emulator allowlist is pinned in code to the `pr2_operator` fixture UID; credentials or command JSON cannot choose it. This fixture is not an approved production authority. Run `npm --prefix firebase/functions run provision:pr2:emulator -- /absolute/path/to/input.json` from the repository root. The CLI requires the exact demo project and loopback Auth/Firestore endpoints before acquiring services, signs into the Auth Emulator, verifies the issued ID token and pinned operator UID with Admin Auth, reloads the target Auth user, and executes one Firestore transaction. It prints only the committed UID, workspace ID, membership revision, and replay flag. Keep the input file and environment out of shell history and source control.
5. If the process stops after an unknown outcome, run the **same** input and idempotency key again while the verified trainer identity and emulator cap configuration remain the same. A matching, internally consistent receipt and audit return `replayed: true` without rewriting the state. A changed payload or damaged receipt/audit fails. Review `operatorAudit/{receiptId}` and `lifecycleCommands/{receiptId}` using trusted operator tooling; neither path is mobile-readable.

The emulator-only caps are 2 active catalog contributions per account and 5 active workspace members. They validate the same shared catalog projection used by membership transitions. An existing account must be schema-current, active, email-matched, and have an inactive zero-count entitlement at the exact expected revisions. Existing membership references, workspace, profile, or conflicting receipt stop the command. An Auth identity must already exist and be verified: Firebase Auth creation cannot be included in the Firestore transaction. A failed transaction leaves the account, workspace, membership, profile, counters, and audit unchanged.

Production provisioning is disabled. Before enabling any production operator command, Josip/security must approve the operator channel and eligibility proof, product/engineering must approve caps and versioned lifecycle configuration, and operations must complete the production migration and audit gates in the [Milestone 4 plan](milestone-4-plan.md).
