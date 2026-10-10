# Milestone 4 PR 2 implementation report — 2026-10-09

Branch `feature/milestone-4-pr2-trainer-provisioning` began at `60810a12d09442b72c193eb1f7f489cfba33c3cd` (`main` and `origin/main`, merged PR #4). Final review started at that same HEAD with the 20 intended PR 2 files uncommitted and unstaged. No Firebase resource was deployed and no production data was accessed.

## Scope and authority

PR 2 supplies an emulator-only operator CLI, not a mobile callable. The CLI authenticates a verified Auth Emulator operator, checks its UID against the fixed `pr2_operator` fixture allowlist, reloads the verified target Auth identity, then calls one bounded Firestore transaction. The transaction creates or validates the schema-current account and empty entitlement, and atomically creates the first active workspace, owner trainer membership, minimal profile, membership reference, active entitlement, both workspace counters, caller-bound immutable receipt, and operator audit. It rejects existing workspace/profile/membership/reference, any other active reference, mismatched account email or schema, stale revision, invalid UID/path, invalid cap, ineligible Auth identity, unauthorized operator, and changed-payload replay. Ordinary membership transitions and bootstrap use the same bounded counter projection. An unknown-outcome retry uses the original key and returns the original receipt without writes.

The [decision register](milestone-4-plan.md#2-decision-register) records that production operator authority, trainer eligibility policy, and product caps are still open. The local caps mean **2 active catalog contributions per account and 5 active roster members per workspace**. They are emulator fixtures, not approved trainer-account or workspace-count limits. Both emulator endpoints must be loopback, both configured project IDs must name `demo-tillfailure-m3`, and no provisioning endpoint is exported in `firebase/functions/src/index.ts`. The [operator procedure](milestone-4-pr2-operator.md) covers the local command and retry. Auth identity creation is a separate prerequisite because it cannot join the Firestore transaction.

The mobile product gate still trusts only fresh server account, bounded membership-reference, workspace, and exact membership reads. A signed-in account without membership remains at WorkspaceGate with **Recheck access**. Verified trainer membership selects a minimal trainer home state with no invitation, programming, scheduling, or role-selection controls. The existing revocation listener closes that root; cache-only data cannot promote a role. The PR 2 profile Rule permits only the verified active trainer's exact path-bound profile read and denies all mobile writes and lists.

## Final review findings and fixes

- The provisioning primitive originally accepted any nonempty Firestore emulator host, including a non-loopback host. It now rejects missing, inconsistent, non-demo, and non-loopback Auth/Firestore configuration before reference construction or service acquisition in the CLI.
- The original CLI allowlist could be set to the caller's own UID through an environment variable. The emulator command now pins authority to `pr2_operator`; a verified ordinary user with a matching self-allowlist is rejected before writes.
- The development fixture seeder originally updated existing Auth users and replaced the trainer account/entitlement. It now refuses reused fixture identities or protected Firestore state and uses create-only account/entitlement writes. Auth creation remains outside Firestore atomicity; an interrupted seed can leave only fixture Auth users and requires a fresh emulator project.
- A matching receipt could return an unchecked stored result with a mismatched audit. Replay now validates its result and the audit's schema, action, verified operator UID, target, workspace, request hash, and receipt ID. Inconsistent evidence is rejected without rewriting protected state.

## Verification

| Layer | Attribution and result |
|---|---|
| Auth/Firestore/Functions/Storage emulators, named demo project | **Fresh final review:** full `npm test` **109/109 passed**, 0 failed, after the environment, receipt/audit, seeder, and pinned-operator CLI regression cases. The full Rules, catalog lifecycle, and callable suites ran in this invocation. After adding authorized CLI commit/replay assertions to the existing case, the focused Auth/Firestore provisioning suite passed **9/9**; unchanged suites were not rerun. |
| TypeScript build | **Fresh final review:** `npm run build` passed after the backend fixes. |
| Shared Kotlin | **Retained from the PR 2 implementation run, not rerun in final review:** `./gradlew :shared:allTests`: Android host **94/94**, iOS simulator **94/94**, 0 failures. The new test proves pending → cache rejection → online trainer root → revocation. |
| Android build | **Retained:** `:shared:assembleAndroidDeviceTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease` passed. |
| Android real Firebase adapter | **Retained:** API 29 emulator, focused `AndroidProductIdentityTest#pr2OperatorProvisioningChangesOnlyServerVerifiedTrainerRoot`: **pending 1/1** before operator commit and **verified 1/1** afterward. Pending recheck stayed at WorkspaceGate with profile denied; a new instrumentation process restored the existing native Auth session without signing in again, then reached trainer home and read the server profile after commit. |
| Operator CLI | **Retained from implementation:** local fixture seed created no workspace. **Fresh final review:** the CLI test rejected a verified ordinary Auth user attempting to set its own UID as the allowlist without protected writes, then authenticated the pinned `pr2_operator` fixture, committed `pr2_trainer` / `cli_workspace` once, and returned `replayed:true` for an unchanged retry. The positive path was verified in the focused **9/9** run after the authority pin. |
| iOS real Swift Firebase adapter | **Retained:** ad hoc signed Debug app on booted iOS 26.2 iPhone 16e simulator: `PR2_IOS_PENDING auth/gate/profile=PASS`; after the separate operator commit and process restart, `PR2_IOS_VERIFIED auth/gate/profile=PASS`. The second launch restored the existing native Auth session and read the profile from the server. |
| iOS build isolation | **Retained:** ad hoc signed Debug and unsigned Release simulator builds both passed. The PR 2 Swift harness and emulator-port reads are inside `#if DEBUG`; Release injects no local Firebase bridge. |

The fresh emulator run used local Auth `127.0.0.1:9099`, Firestore `127.0.0.1:8180`, Functions `127.0.0.1:5001`, and Storage `127.0.0.1:9280`; Firestore's default `8080` was occupied in the implementation run. The earlier implementation run's first full-suite attempt used nondefault Auth/Functions ports while an existing callable test hardcoded `9099/5001`, so its callable cases could not connect. Repeating that earlier suite on the expected ports produced **105/105**, superseded by the fresh **109/109** result. The failed port attempt is not counted as passing evidence.

## Exact changed files

- `docs/firestore-schema.md`
- `docs/firestore-security.md`
- `docs/milestones/milestone-4-plan.md`
- `docs/milestones/milestone-4-pr2-operator.md`
- `docs/milestones/milestone-4-pr2-report.md`
- `firebase/firestore.rules`
- `firebase/functions/package.json`
- `firebase/functions/scripts/seed-pr2.mjs`
- `firebase/functions/src/catalog.ts`
- `firebase/functions/src/input-validation.ts`
- `firebase/functions/src/provisioning-cli.ts`
- `firebase/functions/src/provisioning.ts`
- `firebase/functions/test/provisioning.test.ts`
- `firebase/functions/test/rules.test.ts`
- `iosApp/iosApp/iOSApp.swift`
- `iosApp/iosApp/TrainerProvisioningHarness.swift`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidProductIdentityTest.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityViewModel.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/identity/IdentityViewModelTest.kt`

## Remaining limits and review

- The product operator channel, eligibility proof, and finite production cap values still need Josip/security/product/engineering approval. Production provisioning remains disabled.
- Both native hosts restored an existing Auth session in a new process after the operator commit. The full PR 4 trainer shell, invitations, client onboarding, and offline grants are outside PR 2.
- No physical iOS Keychain/Data Protection property was tested; simulator results establish only simulator behavior. Existing PR 1 clean-departure evidence retains the attribution in the [PR 1 report](milestone-4-pr1-report.md) and was not claimed as a fresh PR 2 rerun.
- Final diff audit found no generated output, production secret, local emulator configuration, or machine-specific file among the 20 intended changed files. The fixed password literal in the backend test is only for newly created Auth Emulator fixture users; the temporary emulator configuration was removed after testing. No mobile product source references the operator CLI or operator credentials; the Debug-only iOS harness reads a trainer fixture password from its test environment. `git diff --check` passed before staging; new files have no trailing whitespace or missing final newline. No application dependency or production Firebase resource changed.

## PR #5 review follow-up — 2026-10-10

The [Codex review finding](https://github.com/krajinov/TillFailure/pull/5#discussion_r4230880620) identified workspace ID reuse after deleting only the parent document. Firestore retains subcollections, so an old active membership could become readable under a newly created active workspace with the same ID. Before any provisioning writes, the transaction now checks bounded `limit(1)` reads of that workspace's membership, trainer-profile, and client-profile collections and rejects any orphaned data. The operator procedure requires investigation rather than deleting retained records to force reuse.

Fresh follow-up verification: TypeScript build passed; the named Auth/Firestore/Functions/Storage emulator and Rules suite passed **110/110**, including a new regression that seeds each orphaned collection under a missing parent and proves the rejected command leaves the orphan, account, entitlement, workspace, receipt, audit, and new membership unchanged. Android and iOS identity/session code did not change; the native results above retain their original attribution. This follow-up changes only `firebase/functions/src/provisioning.ts`, `firebase/functions/test/provisioning.test.ts`, this report, and the operator procedure. Production gates remain open.
