# Open questions and decision blockers

Status: **decision register**
Review date: **2026-09-10**

These items are unresolved unless explicitly marked. “Blocks” names the first milestone that cannot be accepted without the answer; earlier planning/spikes may proceed.

## Product and policy

| Priority | Question / decision owner | Blocks | Current safe assumption |
|---|---|---|---|
| P0 | Who may create the first trainer workspace, and is trainer identity manually approved? Product/security | 4 | No public self-elevation to trainer; use a trusted bootstrap process. |
| P0 | Must an invitation’s email exactly match the authenticated account, and may users belong to more than one workspace/account? Product/security | 4 | Exact binding; one active single-trainer workspace context at a time, with explicit switching if multiple memberships exist. |
| P0 | Applicable privacy/health-data jurisdictions, consent basis, age minimum, and controller/processor responsibilities? Legal/product | 4/5/8/9/11/12 | Does not block the non-product foundation or emulator spike; minimize collection and do not ship affected features before review. |
| P0 | Retention/export/deletion policy for accounts, unsynchronized workouts, progress, messages, private notes, appointments, audit records, backups and media? Legal/product | 4/5/8/9/11/12 | Preserve account-isolated data; do not promise hard-delete timing or implement irreversible cleanup without policy. |
| P0 | Which slot quantum from `{1, 5, 10, 15}` minutes, maximum/allowed durations and buffers, booking window, cutoffs, no-show behavior, and booking rights? Product/engineering | 10 | Deterministic UTC bucket contention is decided in the proposal; product values are not. Choose within [schema budgets](firestore-schema.md#deterministic-bucket-coverage-and-bounds), approve conservative outward rounding, and freeze quantum at scheduling enablement. No default appointment duration is selected. |
| P0 | What account/workspace membership caps fit product needs and permit atomic catalog entitlement lifecycle changes? Product/engineering | 4 | Configure bounded `maxMembershipsPerAccount` and `maxMembershipsPerWorkspace` within [lifecycle transaction budgets](firestore-security.md#system-catalog-entitlement-lifecycle). Reject unsupported fan-out; do not enable unbounded lifecycle operations or substitute delayed claims/triggers. |
| P1 | Which onboarding health/constraint fields are necessary, who may edit/read them, and is trainer acknowledgement required? Product/legal | 4/5 | Collect the minimum structured data; no broad medical record. |
| P1 | Are profile photos, progress photos, exercise media and message attachments launch-MVP requirements? Product/legal/design | 5/9/11 | Text/data flows first; advanced photo comparison remains post-MVP; hide absent controls. |
| P1 | Progress measurements/types/units and correction rights? Product | 9 | Support a small typed basic set with explicit unit; client owns entries, trainer reads. |
| P1 | Message edit/delete windows, read receipts, retention and notification-preview policy? Product/legal | 11 | Append-only messages with sender tombstone policy to be defined; pushes contain no body. |
| P1 | Can trainers edit client workout logs/progress, or only comment/review? Product | 8/9 | Trainer reviews/comments; client-originated measurements remain attributed and are not silently overwritten. |
| P1 | Private trainer notes: offline availability, export/discovery, retention after relationship revocation? Legal/product | 5 | Trainer-only boundary; minimize offline caching; no client visibility. |
| P2 | Exercise catalog source, search requirements, localization and media licensing? Product/content | 6 | Small curated system catalog plus trainer custom exercises; no third-party full-text service by default. |
| P2 | Template draft collaboration/autosave and published-version removal rules? Product | 6 | Single trainer edits; immutable published versions; archive referenced content. |
| P0 | Approve the proposed maximum seven-day restricted offline eligibility, or choose a shorter duration? Product/security | 4/8 | Seven days is a recommendation only; server Rules remain authoritative and reconnect revalidates. |
| P0 | When access is revoked/eligibility expires with unsynchronized workout data, permit retain/retry, support/export, and explicit discard under what retention window? Product/legal/security | 4/8/12 | Keep a locked UID-scoped recovery partition; never silently delete or expose it to another account. |
| P0 | May sign-out/account switch be blocked by pending critical work, and when is explicit destructive discard allowed? Product/security | 4/8 | Simplest MVP: one active account; synchronize/cancel, or explicit approved discard. No hot switch. |

## Technical and operational

| Priority | Question / decision owner | Blocks | Current safe assumption |
|---|---|---|---|
| P0 | After the seven-case parity spike, approve conditional ADR-001 official SDKs/Swift bridge or select GitLive based on exact required APIs? Engineering | 3 | Swift SDK 12.18.0 in `iosApp`, narrow callback bridge and `iosMain` wrapper are provisional; no `commonMain` Firebase claim. |
| P0 | Dev/stage/prod Firebase project ownership, regions, data residency, budgets, service accounts and deployment approval process? Platform/security | 3 | Emulator only until separate authorization; no deployment from planning. |
| P0 | Exact TypeScript 5 patch and lockfile after Node 22 + Functions 7.3.2 compile test? Engineering | 3 | Unresolved; do not scaffold/package-install yet. |
| P0 | Do the selected backend SDK and emulator tests prove empty-range bucket contention, receipt replay, and atomic entitlement lifecycle within the proposed write/index budgets? Engineering | 3 | Required future proof from [testing-strategy.md](testing-strategy.md#planned-booking-contention-tests); this documentation change is not runtime verification. If bounds/lifecycle are infeasible, revise policy/layout explicitly before feature implementation. |
| P1 | Which smallest durable mechanism implements `OfflineAccessGrant`, download manifest, workout recovery snapshot, mutation journal and switch marker atomically/encrypted on both targets? Engineering spike | 3/8 | App-owned persistence is required for critical rejected-write recovery; do not default to Room/another general database, but allow evidence-driven persistence. |
| P1 | Can Rules-based `baseRevision` ordinary writes expose reliable acceptance/rejection and `lastOperationId` reconciliation after restart on both native SDKs? Engineering spike | 3/8 | If not, evaluate immutable server operation records; Milestone 8 stays blocked. |
| P1 | Can Apple/Android Storage uploads recover from process death with durable file references under platform sandbox rules? Engineering spike | 3/9/11 | Copy approved media into app-owned temporary/pending storage and show pending status; verify cleanup. |
| Resolved 2026-09-01 | Does Navigation 3 1.1.1 plus lifecycle 2.11.0-beta01 and Koin 4.2.2 compile and scope ViewModels correctly on the frozen toolchain? Engineering spike | 1 | Both targets compile/launch; Android pop/recreation passed and the user manually passed equivalent interactive behavior on an iPhone 16e simulator. Process-death/durable restoration remains out of scope and unverified. |
| Resolved 2026-09-01 | Keep frozen Compose 1.11.1 prerelease Material/lifecycle mappings or run a coordinated upgrade to newer stable versions? Engineering | 1 | Keep the existing Compose 1.11.1, Material 3 1.11.0-alpha07, and lifecycle 2.11.0-beta01 cluster for Milestone 1; no unrelated upgrade was made. Re-evaluate only as a coordinated future toolchain change. |
| P1 | Does the one-active-account sign-out protocol meet shared-device risk, or must Firestore persistence be disabled? Security/engineering | 3/4/12 | `clearPersistence` removes pending/cache only after termination and is not secure erase; cleanup failure blocks another account. |
| P1 | App Check rollout and attestation behavior for debug/emulator/unsupported devices? Security/product | 12 | Evaluate before production; never treat App Check as user authorization. |
| P2 | Planned-workout materialization horizon and template draft persistence mechanism? Engineering/product | 7 | Prefetch a bounded next-workout horizon; exact count configurable after usage evidence. |
| P2 | Analytics consent model, allowed event dictionary, crash collection defaults and regional controls? Legal/product | 12 | Minimal disabled-or-consented collection according to final policy; no private content. |
| P2 | CI provider, macOS/Xcode availability, physical Android/iOS device ownership, APNs signing, and release signing custody? Engineering/operations | 1/11/12 | Report missing platform checks; do not waive them. |

## Architecture selected for the proposal

- **Booking contention:** `workspaces/{workspaceId}/bookingSlots/{trainerId}_{utcBucket}` covers the complete outward-rounded buffered UTC interval. Trusted transactions acquire/change/release locks with the appointment and deterministic command receipt; querying an empty appointment range is not a lock. Quantum values/product timing remain open above; the shared-contention invariant does not.
- **Global catalog authorization:** `users/{uid}/authorizations/systemCatalog` is the fixed Rules-readable account entitlement, atomically maintained from membership/workspace lifecycle with account-status gating. Memberships remain authoritative for workspace-scoped roles/data. This does not use arbitrary membership lookup in Rules, delayed custom claims, or a client flag.
- Both are documentation decisions requiring Milestone 3 emulator proof, Milestone 4 lifecycle integration, and Milestone 10 scheduling implementation. ADR-001's native SDK/bridge boundary and approval gates remain unchanged.

## Design clarifications

| Priority | Question | Current treatment |
|---|---|---|
| P1 | Approve preserving visible 44-unit controls while enforcing 48 dp effective Compose hit targets? | Proposed accessibility reconciliation; no Pencil change made. |
| P1 | Responsive layouts across supported phones, landscape policy, dynamic type, long localization and reduced motion? | Use adaptive/scrolling layouts and test; static frames are not complete evidence. |
| P2 | Which settings/profile surfaces are essential at launch, and what should replace long-term business controls? | Include privacy/security/account essentials; omit post-MVP controls rather than disable them. |

## Non-blockers and explicit exclusions

Payments, invoices, subscriptions, session credits, group sessions, advanced analytics/photo comparison, multi-trainer workspaces, public trainer discovery, AI programming, wearables/nutrition, and desktop/web targets are not questions for the MVP plan. They remain deferred unless scope is explicitly reopened.
