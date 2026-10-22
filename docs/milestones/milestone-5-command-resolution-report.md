# Milestone 5 prerequisite — own-command outcome resolution

Status: **emulator implementation available for review; acceptance remains qualified by the interaction and operational gates below.** Josip approved the protocol and initial compatibility scope on 2026-10-21. Membership revocation execution/UI and production remain excluded.

## Baseline and scope

Started on `docs/milestone-5-pr4-revocation-plan` at `ce3bde2ed177c873c5b0f55c82949b8f70d18e5a`, the merge of PR #12. Remote main matched that commit; neither requested branch existed remotely. Preserved and reviewed the four planning files, recorded the explicit approval, and committed only those files as `c571524` (`docs(recovery): approve own-command resolution prerequisite`). Created `feature/milestone-5-command-resolution` from that planning commit. Implementation and subsequent documentation updates were left uncommitted and unstaged at the original handoff. The 2026-10-22 finalization below supersedes that handoff status; the planning commit is preserved.

Read repository instructions, the milestone/prerequisite/revocation plans, current schema/security/offline/navigation documents, PR 1–3 reports and actual server/shared/native lifecycle, identity, transport, ledger and persistence code. No applicable AGENTS.md was found. Applied repository MVI/Compose guidance and the Android CLI skill for device interaction. Preserved prior acceptance gaps and their attribution. No Rules, indexes, dependencies, design source or Firebase cloud resources changed.

## Implemented protocol and writer audit

`commandResolutionEmulator` is guarded by the named demo project and loopback Auth/Firestore endpoints. It initializes the Admin app before fresh bearer verification, checks token revocation and non-anonymous caller identity, reloads enabled verified Auth, and transactionally requires the original UID's active schema-1 account, valid lifecycle revision and current email binding. No workspace membership, inviter, owner, assignment or catalog entitlement is required for an own outcome. Normal business endpoints retain their existing fresh business authorization.

The strict version-1 packet selects exactly one of six original descriptors: invitation create/resend/revoke/accept, onboarding completion or basic-profile update. Unknown fields, wrong types, unsafe IDs, incomplete acceptance binding and unsupported revisions fail closed. The packet has an 8,192-character outer bound in addition to existing per-field bounds. Explicit adapters preserve original canonical hashes and schema-1 receipt formats. Six shared/backend golden vectors include invitation null-token hashing, acceptance digest and Unicode basics.

The response contains exactly `protocolVersion`, `commandKind`, `commandId`, `requestHashVersion`, `requestHash`, `outcome`, `decidedAtMillis`. Outcomes are `applied`, `not-applied-conflict` (the supported durable profile conflict) or `not-applied-fenced`. Per-kind receipt validation checks exact fields, identity/hash, result relationships and timestamps. No workspace/profile/contact/history, invitation bearer or authorization state is returned. No general receipt get/list, resource enumeration, repair or role restoration exists.

| Effect writer sharing `lifecycleCommands` | Audit result |
|---|---|
| Invitation create/resend/revoke/accept | All four branches read the caller/key receipt in the effect transaction and atomically create it with effects. Existing fence, unsupported schema, wrong kind/hash or conflicting record cannot be overwritten. Separate invocation-throttle writes are admission metadata. |
| Onboarding completion | Global basics, workspace completion and schema-1 receipt remain one transaction. Existing consumed key prevents effects. |
| Basic-profile update | Account update or durable conflict and receipt remain atomic; `profile-get` stays a read. Existing consumed key prevents effects. |
| Membership/workspace/account lifecycle primitives | All three retain consumed-key rejection; each now checks reserved generation configuration inside its effect transaction. No new public lifecycle endpoint is exposed. |
| Operator trainer provisioning | Checks generation inside its existing transaction; receipt, audit and bootstrap effects remain atomic and caller-bound. No new operator authority is introduced. |

All ten effect variants now validate reserved generation coverage before any effect write. Existing recognized receipts retain normal endpoint authorization/replay checks; fences/unknown/conflicting records consume the whole key. `assignmentCommands` and workspace `bookingCommands` are distinct namespaces, outside this six-variant mobile slot and resolver. Their existing primitives are unchanged. Future revocation must become a separately authorized seventh protected variant using this same contract.

A resolver reads the exact global account and deterministic receipt path. A valid existing receipt/fence projects its historical outcome without current resource reads or rewriting the decision. Covered absence creates an immutable schema-2 fence at that same path, atomically with its admission budget charge. Concurrent effect transactions must observe that record or fail/retry their read/create conflict; no effect can commit without its receipt. Absence, cancellation, timeout and retry denial alone never settle work.

Admission is bounded to 120 requests per UID per minute and a configured lifetime fence budget per UID/generation (fixture default 20, accepted range 1–100). Replays consume request admission but not a second fence charge. Each transaction has at most three attempts, fixed point reads and no queries: request admission is one read/one write; resolution is two reads/no writes for an existing decision, or at most four reads/two writes for a fence. A five-second elapsed-work check gates transaction entry and fence creation; it is not a claim to cancel an already outstanding RPC or to prove non-commit on timeout.

## Coverage configuration and provenance

The stable demo generation is `b91f0176ec74475e8cf7406e25b7e9d3`. New keys use `or1-{generation}-{UUID}`. This marker is not a credential. Every shared-namespace writer rejects unknown/retired reserved generations, malformed reserved keys, missing configuration, mismatched provenance or disabled execution. Existing terminal own projections can still be read when absence coverage is disabled, provided identity/binding and the record remain valid.

The concrete trusted procedure is:

1. Start a new task-owned, loopback-only `demo-tillfailure-m3` dataset with all audited writers. Supply one stable nonsecret `TF_RESOLUTION_PROVENANCE_ID` to the Functions supervisor and trusted tooling. Do not expose traffic during initialization.
2. Build Functions, then run `node firebase/functions/scripts/command-resolution-config.mjs initialize` with the demo/loopback environment and `TF_RESOLUTION_HISTORY_ATTESTATION=new-task-dataset-no-prior-effects`. Initialization requires that explicit operator assertion, refuses existing users/workspaces/invitations/receipts and uses create-only configuration. The empty checks are safeguards, **not proof against deleted history**.
3. Finish and stop reset-oriented suites before starting a preserved native supervisor. Do not run concurrent Firebase CLI supervisors for the same project even on distinct ports: project discovery can collide. Verify a complete export and its metadata while the sole supervisor is still running, before shutdown. Preserve the entire dataset, configuration, consumed-key barriers and provenance together on restart/export/import. Resume only an attested intact export with the same provenance and audited writers. No startup script generates a new generation or automatically re-enables coverage.
4. If provenance/history is uncertain, stop serving and run the guarded `disable` operation with the matching provenance. It disables verified history, execution and absent-record fencing. Do not initialize over business history, combine old receipts with newer effects, reset/reseed to obtain a desired result or infer safety from a missing receipt. There is deliberately no repair/re-enable operation here; further operational review is required.

There is **no automatic detection of arbitrary dataset-history loss**. A privileged actor can violate provenance; hashes are not MACs against Admin forgery. No receipt/fence TTL, cleanup, production retention duration, permanent-key compaction or production rollout is implemented. Stable configuration and intact records are mandatory while a covered invocation can still execute/retry. Rollback must retain barriers and fail-closed writer guards.

The create-only native fixture script accepts optional task prefix/platform/mode selection, refuses existing identities/accounts/workspaces and uses existing provisioning/invitation/onboarding operations. Temporary credentials are supplied outside the repository. Tests that deliberately reset their isolated test database must never target a preserved native fixture dataset.

## Native settlement and recovery experience

The existing encrypted UID envelope and single `InvitationLedger` gain optional pending terminal proof and one bounded last-settled proof. Pending acceptance gains its original token digest for new commands; raw bearers remain transient. Legacy pending commands keep their original key and payload. No second recovery store/queue is added.

Validated normal success/conflict acknowledgments and resolver outcomes share two durable envelope writes under the existing native issuance/ownership lock: retain the pending payload with validated proof, then retire pending work and retain the last proof. Restart can finish retirement from an intact pending proof. Failure before proof retains unknown work; failure during retirement retains payload plus proof. Normal acknowledgments preserve only fields actually received, with delivery bearers removed; no server timestamp or minimal-outcome fields are invented. The last proof is an account-owned bounded settlement artifact, not a role grant or general history UI.

Resolution preparation durably marks the exact original command as started before transport. New local business retries then stop, including Profile recovery. Already in-flight originals remain governed by the server receipt/fence invariant. Exact UID/key/kind/payload matching prevents ordinary or resolver callbacks from clearing a newer command. Native freeze, UID/generation/epoch fencing, presentation cancellation and the existing clean-departure ordering remain in use. Apple startup proof retirement also shares the native recovery/ownership lock; Android recovery reads use its callback/ownership lock.

The existing Invitations/Locked Recovery surface now offers Resolve for the current protected variant when identity and binding permit. Retry explicitly resubmits the original business request; Resolve may foreclose an uncommitted key and requires a command/prompt-bound confirmation. Cancel/Back does not issue resolution. Pending, applied/not-applied, unavailable, identity-required, binding-required and storage-failure guidance preserve the original work where appropriate. Terminal proof can request normal reauthorization but cannot open a role shell, refresh protected content or choose another workspace. No payload/key/digest/receipt is saved in navigation or application diagnostics.

## Compatibility limits

- Supported intact schema-1 receipts can project historical outcomes without workspace authority. Normal business replay still requires its original authorization.
- An absent legacy key is unavailable: missing history cannot prove non-commit. Do not rekey it into the covered generation.
- A legacy acceptance lacking its original digest needs the reopened original link to reconstruct binding. Matching invitation ID/version alone is insufficient. No token recovery from server data is offered.
- Disabled/deleted/unavailable Auth, missing/disabled/unsupported global accounts, email mismatch, corrupt/unsupported receipts and damaged/missing protected storage remain explicit locks. No identity/account restoration, privileged support override, destructive discard or export is introduced.
- Older clients that cannot decode new proof fields lock safely. Baseline business writers already reject schema-2/unknown receipts; the modified writers additionally enforce reserved-generation coverage. This is source/contract compatibility evidence, not a separate deployed-old-binary rollout test.

## Original implementation verification (2026-10-21)

The full backend/Rules suite passed **192/192**, zero failures/skips. Its 13 resolver cases cover commit-first and resolver-first decisions, a real transaction aborted after reading C absent and retried against the winning fence, concurrent resolution/commit, six valid delayed business requests, exact immutable projection/replay, membership/inviter/workspace authority loss, fresh callable-worker Auth, tampered/foreign/legacy denial, disabled global/Auth identity and finite generation/budget controls. The post-read retry test explicitly injects an ABORTED transaction at that boundary; it is not a claim that a resolver bypasses an actively held pessimistic read lock. Source/update-time assertions and existing full-suite Rules/lifecycle cases prove unchanged business resources and no partial writes. Lost replies are modeled by dropping results and exact replay, with actual native response holds below.

Shared final-source tests passed **189/189 Android** and **190/190 iOS**, zero failures/errors/skips. Android Debug/Release and device-test APK assembly passed. Final signed iOS simulator Debug and unsigned simulator Release builds both succeeded. Tests cover all six canonical bindings, exact DTO validation, durable start, retry suppression, stale confirmations, late responses, proof-write/retirement failures and corrupt partition/proof locks. The existing platform-specific test difference remains; these are not native SDK test counts. Earlier PR reports retain their original evidence; their totals are not added here.

Native Android passed **3/3 restart scenarios** on the final core transport/storage implementation: committed command with completion held, command held before transport and fenced after restart, and process termination after durable normal proof but before retirement. Each stage was host-terminated without resetting app data; resume verified exact original/proof, revoked fixture membership, no role restoration, blocked premature departure/B sign-in and clean A/B departure. The same three scenarios also passed an earlier fixture run; they are repetitions, not six distinct tests.

Native Swift passed **3/3 restart scenarios**, with **23 substantive resume assertions / 0 FAIL** (8 applied, 8 fenced, 7 pending-proof), excluding polling/bounded-state lines. Stage assertions separately verified original identity/protected work and blocked second-account admission. Host process termination, native Auth/Firestore/Functions, encrypted files/Keychain and root recreation were used. These invoke SDK/model lifecycle paths, not touch or OS observer dispatch. The final later changes were recovery presentation text/fields and profile retry suppression, covered by shared tests/builds and the Android interaction below.

Actual Android product interaction used a new UI fixture and the real role root. Sign-in/Profile read succeeded through a temporary task-owned Functions relay; stopping that relay before Save produced unresolved protected work. The app process was terminated, the fixture authority changed using the existing catalog primitive, and the same partition restarted against the real endpoint. Resolve opened a confirmation; Cancel preserved work. At **200% OS text scale**, scrolling reached Resolve, Back dismissed the confirmation without submission, and confirmed Resolve displayed not-applied/no-later-execution guidance. Clean Sign out returned to Auth with empty fields. A screenshot was visually inspected: dialog copy wrapped and both actions fit/reached. Restored the original absent font-scale setting and read it back. No iOS touch/accessibility pass is inferred.

The final identity-eligibility refinement additionally preserves disabled/missing/unsupported global-identity causes nested under Locked Recovery; shared regression/build evidence covers it. The actual Android fixture had eligible identity throughout, so this does not claim an actual disabled-identity UI run.

## Failed attempts and evidence qualifications

The first backend run exposed two fixture expectations using profile revision 1 after onboarding had already advanced it; those assertions were corrected before the complete suite passed. Final binding review also separated invitation token-version/global-lifecycle bounds from onboarding profile-revision bounds, preserving supported high-version legacy commands without arithmetic overflow. A later race-test compile caught an overloaded Firestore snapshot type; its test-only cast was corrected. The expanded full run passed 191/192 before exposing a fixture account without its required inactive catalog entitlement in the new six-variant delayed-execution case. That fixture was made consistent without relaxing business authorization, then the full suite was rerun.

The first native emulator configuration used absolute source paths; Firebase treated Functions as a missing relative path. The held iOS callback was therefore not proof of a server commit. The dataset and pending partition were exported intact, configuration corrected and the preserved dataset imported. Native execution then exposed a real resolver bootstrap defect (Admin Auth before Admin app initialization); this was corrected, and a fresh callable-worker regression was added. Android retried its retained original key and settled successfully after the fix. The earlier iOS command returned identity-required after the preserved Auth export/import; that original partition remains protected without identity repair/discard. Fresh distinct iOS fixtures establish the eligible-identity applied/fenced/proof restart results. Do not count the failed iOS attempt as an applied or fenced pass.

Sandbox-only simulator/Gradle lock attempts failed before the authorized elevated checks succeeded. Two automatic approval-review timeouts did not execute their requested launch/build actions; a permitted retry succeeded. Android layout inspection once conflicted with a concurrently running instrumentation harness and returned an unrecognized server response; later interaction ran after native instrumentation completed. These are recorded as tooling/setup failures, not successful UI/native proofs.

## Remaining gates

Review and acceptance of this prerequisite still precede any separately authorized revocation implementation. No membership revocation product operation/UI, reassignment/archive/account-disable workflow or automatic cancellation/deletion is included. Production identity recovery, legacy migration, storage/key damage, privacy/retention/key retirement, backup integrity, support/export/discard and rollout policy remain open.

Actual iOS touch/keyboard/Back/scrolling/large-text checks remain missing. The previously documented Simulator accessibility `AXError.cannotComplete` surface was not repeatedly retried, and Swift SDK harness results are not touch or OS observer evidence. TalkBack/VoiceOver, OS network/foreground observer dispatch, physical storage/backup/shared-device and earlier Milestone 4 / PR 1–3 interaction and G1–G11 gaps retain their attribution. Emulator proof does not close these production/platform gates.

## Exact changed files and handoff audit

The final implementation inventory contains **43 intended files**: the original 40 plus the three onboarding retry-regression files added during finalization. The planning commit also contains the earlier four-file approval preparation; its subsequent updates are included here. The planning commit remains intact.

- `README.md`
- `docs/firestore-schema.md`
- `docs/firestore-security.md`
- `docs/milestones/milestone-5-command-resolution-design.md`
- `docs/milestones/milestone-5-command-resolution-report.md`
- `docs/milestones/milestone-5-plan.md`
- `docs/milestones/milestone-5-pr4-plan.md`
- `docs/navigation.md`
- `docs/offline-sync.md`
- `docs/open-questions.md`
- `firebase/README.md`
- `firebase/functions/scripts/command-resolution-config.mjs`
- `firebase/functions/scripts/seed-m5-resolution.mjs`
- `firebase/functions/src/basic-profile.ts`
- `firebase/functions/src/catalog.ts`
- `firebase/functions/src/command-identity.ts`
- `firebase/functions/src/command-resolution.ts`
- `firebase/functions/src/index.ts`
- `firebase/functions/src/invitations.ts`
- `firebase/functions/src/onboarding.ts`
- `firebase/functions/src/provisioning.ts`
- `firebase/functions/test/command-resolution.test.ts`
- `iosApp/iosApp/CommandResolutionHarness.swift`
- `iosApp/iosApp/FirebaseNativeBridge.swift`
- `iosApp/iosApp/iOSApp.swift`
- `shared/src/androidDeviceTest/kotlin/com/delminiusapps/tillfailure/firebase/AndroidCommandResolutionTest.kt`
- `shared/src/androidMain/kotlin/com/delminiusapps/tillfailure/firebase/AndroidFirebaseSpikeClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/identity/IdentityRoot.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolution.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationClient.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/invitations/InvitationViewModel.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/Onboarding.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/persistence/PersistencePrototype.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/profile/ProfileScreen.kt`
- `shared/src/commonMain/kotlin/com/delminiusapps/tillfailure/profile/ProfileViewModel.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/CommandResolutionTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/InvitationTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/invitations/RecoveryHardeningTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/onboarding/OnboardingTest.kt`
- `shared/src/commonTest/kotlin/com/delminiusapps/tillfailure/profile/ProfileTest.kt`
- `shared/src/iosMain/kotlin/com/delminiusapps/tillfailure/firebase/IosFirebaseBridge.kt`


Original handoff content checks covered all 40 tracked/untracked intended files: no fixture credential, private/API key, machine-specific path, generated output, raw log/export or unrelated file is included; final newlines, local links/anchors, whitespace and `git diff --check` pass. The index was empty at that handoff. Its Release audit found no Android instrumentation class/phase/credential selectors and no iOS command-resolution harness executable symbols, activation/password strings or PASS markers; normal source filename debug information is not executable harness code. Release hosts still inject no operational Firebase identity client.

At the original handoff, task-owned native data/configuration/receipts/fences were exported intact before reset-oriented tests. Only this task's supervisors, temporary relay/configuration, Android AVD and iOS simulator were stopped; device and export data remain preserved, including the unresolved earlier iOS partition. No wipe, receipt deletion, account repair or unrelated-service termination occurred. The original Android font-scale setting was restored. No raw artifact or credential is committed.

Original handoff branch was `feature/milestone-5-command-resolution`, HEAD `c571524`. Planning approval was the only local commit added at that point. Revocation and production remain gated.


## Review and finalization (2026-10-22)

Josip separately authorized review, concrete fixes, implementation commit, normal push and a PR against main. Starting branch was `feature/milestone-5-command-resolution`; full starting HEAD was `c571524fbf4ada163fba69d327a80f2e87ec8e20`. Remote main remained `ce3bde2ed177c873c5b0f55c82949b8f70d18e5a`; the feature branch and its PR did not exist remotely at inspection. The complete original 40-file tracked/untracked implementation was preserved and reviewed, with no unrelated work found. No reset, amend, squash or history rewrite was used.

The review reconfirmed all ten shared-namespace effect variants, atomic effects/receipts, whole-key consumption, both transaction orderings, delayed originals, caller/global identity and exact per-kind projection, legacy binding, coverage loss, request/fence limits, durable proof/retirement, callbacks, issuance/freeze/account isolation and Release guards. Trusted coverage remains an operational assumption: configuration and empty queries cannot prove history was never deleted. The injected post-read ABORTED regression is still distinguished from actual lock scheduling.

Concrete fixes:

- Enforce JSON primitive types before decoding the seven-field terminal outcome; serialization had accepted quoted protocol/timestamp numbers.
- Restore an original-link entry on pending acceptance, and refresh resolution eligibility when it is reopened. A legacy pending acceptance can reconstruct its digest transiently without replacing its key/payload or persisting the bearer.
- Consume a validated, exact-command terminal proof in the native adapters and recovery reads to release the matching transient acceptance binding. Foreign/malformed proofs cannot release it; durable proof remains the sole settlement mechanism.
- Suppress onboarding Retry in both model and presentation after durable resolution start, matching invitation/profile retry suppression.
- Bind Cancel to the current confirmation prompt ID so a late dismissal cannot cancel a newer prompt.

Four new regressions failed against pre-fix source: **22 selected tests, 4 failures**. After fixes, full shared tests passed **193/193 Android** and **194/194 iOS**, zero failures/errors/skips (four additional tests per platform). The stale-Cancel check extends an existing confirmation regression. The full backend/Rules suite was freshly rerun: **192/192**, zero failures/skips; no backend production code was changed by finalization. Android Debug/Release and device-test APK assembly, signed iOS simulator Debug and unsigned iOS simulator Release were freshly successful. Fresh Release binary checks found no Android instrumentation class/phase/password markers and no iOS harness executable symbols, activation/password strings or PASS markers; normal source filename debug information is not harness execution.

Fresh native verification uses a separate, newly attested dataset and new `m5r20261022` fixture identities, with the existing stable generation and a distinct nonsecret provenance ID. The earlier temporary exports/credential were unavailable at finalization. No old identity, command key, receipt history or pending partition was recreated, repaired, reset or treated as resolved. The older unresolved iOS partition remains outside this fresh dataset's coverage. Its fresh attestation does not establish integrity of lost older history. The final export failure below prevents treating this new dataset as restartable coverage.

Fresh Swift native scenarios passed **3/3**, with **23 substantive resume assertions / 0 FAIL**, plus separate stage assertions. They again cover held committed completion, pre-transport fencing and durable-proof restart retirement, root recreation, no role restoration, pending departure lock and clean A/B isolation. An initial host script watched the app-container tmp directory rather than simulator-global tmp, timed out despite a successful held stage, then resumed that same protected command after correcting the observation path; it did not replace the command or reseed its identity.

Fresh Android native scenarios passed **3/3** after a data-preserving cold reboot of the same task AVD. Before that reboot, the emulator package service stalled during APK installation; no scenario command had been issued. A concurrent diagnostic layout read ended with EOF when that stalled emulator was stopped, so it is not interaction evidence. After reboot, held committed completion, pre-transport fencing and durable-proof retirement all passed with actual host termination, original-key restoration, no role restoration, departure blocking and clean A/B isolation.

Final content audit covers all 43 intended files: exact inventory, secrets/fixture credentials, command payloads/digests in added diagnostics, machine paths, generated output, unrelated changes, Markdown links/anchors, final newlines and whitespace. Only implementation, tests and documentation are included; raw logs, temporary configuration, credentials, exports and generated binaries stay outside the commit. The separately authorized implementation commit uses `feat(recovery): resolve own command outcomes after access loss`; it follows the unchanged planning commit and is pushed normally for a PR against main. The PR records the final commit ID. No merge, deployment, production enablement or revocation implementation is part of finalization.

Fixture preservation limitation: the native supervisor and reset-oriented full suite used separate ports, but their Firebase CLI project locator overlapped. After the full suite exited, an explicit native export could no longer discover the still-running native supervisor. Its configured export-on-exit failed for the same reason during graceful shutdown. No intact finalization export was produced; do not recreate these fixture identities/keys, resume their generation coverage, or infer an outcome from this loss. All six fresh native scenarios had already passed and completed clean departure before shutdown; this limitation does not turn the failed export into a pass. Device partitions remain preserved. Any further run requires distinct new fixtures and a separately isolated CLI discovery/project-locator procedure, with verified export before shutdown. This is an operational acceptance gap, not automatic history-loss detection or a production recovery policy.

Finalization acceptance remains **emulator implementation ready for PR review, with qualified acceptance**. All original legacy/identity/storage/operational/production and interaction limitations above remain. The known broken iOS accessibility workflow was not retried. Fresh native SDK/process evidence does not establish touch, OS observer, large-text or accessibility acceptance; the original Android touch evidence retains its 2026-10-21 attribution. Membership revocation is not implemented or authorized by this PR.
