# Open questions and decision blockers

Status: **decision register**
Review date: **2026-09-20**

These items are unresolved unless explicitly marked. “Blocks” names the first milestone that cannot be accepted without the answer; earlier planning/spikes may proceed.

## Product and policy

| Priority | Question / decision owner | Blocks | Current safe assumption |
|---|---|---|---|
| P0 | Who may create the first trainer workspace, and is trainer identity manually approved? Product/security | 4 | No public self-elevation to trainer; use a trusted bootstrap process. |
| P0 | Must an invitation’s email exactly match the authenticated account, and may users belong to more than one workspace/account? Product/security | 4 | Exact binding; one active single-trainer workspace context at a time, with explicit switching if multiple memberships exist. |
| P0 | Applicable privacy/health-data jurisdictions, consent basis, age minimum, and controller/processor responsibilities? Legal/product | 4/5/8/9/11/12 | Does not block the non-product foundation or emulator spike; minimize collection and do not ship affected features before review. |
| P0 | Retention/export/deletion policy for accounts, unsynchronized workouts, progress, messages, private notes, appointments, audit records, backups and media? Legal/product | 4/5/8/9/11/12 | Preserve account-isolated data; do not promise hard-delete timing or implement irreversible cleanup without policy. |
| P0 | Which slot quantum from `{1, 5, 10, 15}` minutes, maximum/allowed durations and buffers, booking window, cutoffs, no-show behavior, and booking rights? Product/engineering | 10 | Deterministic UTC bucket contention is decided in the proposal; product values are not. Choose within [schema budgets](firestore-schema.md#deterministic-bucket-coverage-and-bounds), approve conservative outward rounding, and freeze quantum at scheduling enablement. No default appointment duration is selected. The Milestone 3 spike implements only the narrow participant policy already supported by the architecture (active account/workspace/membership; a trainer member books only their own slots for an active client member; a client member books only themselves with an active workspace trainer; reschedule/cancellation restricted to the stored participants). Broader booking rights—who may initiate, cross-trainer booking, group sessions, delegate booking, and booking-window cutoffs—remain a Product/engineering decision for Milestone 10 before final caps and policy are approved. |
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
| Resolved 2026-09-20 | After the seven-case parity spike, approve ADR-001 official SDKs/Swift bridge or select GitLive based on exact required APIs? Engineering | 3 | Official Android + Apple SDKs with the narrow bridge are accepted. GitLive 2.6.0 has API parity but its stable dependency alignment is older and adds a wrapper owner. |
| P0 | Dev/stage/prod Firebase project ownership, regions, data residency, budgets, service accounts and deployment approval process? Platform/security | 4/12 | Emulator only until separate authorization; no deployment from planning. This does not block local Milestone 3 acceptance. |
| Resolved 2026-09-20 | Exact TypeScript 5 patch and lockfile after Node 22-compatible Functions compile test? Engineering | 3 | Node 22.23.2, npm 11.12.1, TypeScript 5.9.3, Functions 7.4.0, Admin 14.4.0, CLI 15.30.2 and the exact lockfile compiled and passed 17/17 emulator tests. |
| Resolved 2026-09-20 | Do the selected backend SDK and emulator tests prove empty-range bucket contention, receipt replay, and atomic entitlement lifecycle within the configured write/index budgets? Engineering | 3 | The isolated emulator suite passed. Final product caps and complete Milestone 4/10 integration remain separately gated. |
| Resolved 2026-09-20 | Do direct assignment Rules/query checks and atomic materialization fit the spike budgets, including replacement and recovery? Engineering | 3/7 | The isolated snapshot suite passed with direct path checks and bounded transactions. Complete assignment integration and product caps remain Milestone 7 work. |
| P0 | What whole-program size, planned horizon, expiry, archival/history retention and offline clock-integrity policy are supported? Product/security/engineering | 7/8 | Full client-safe copy must fit one bounded publication transaction; reject oversize without partial state. Terminal/expired assignments require new identity; offline allowance ends at the earlier grant bound/assignment expiry and uncertain clock locks access. Exact caps/durations and retention remain unapproved. |
| Resolved for M3 2026-09-20 | Which encryption/data-protection and backup policy protects the UID-partitioned atomic-file envelope? Security/engineering | 8/12 | Android Keystore AES-256-GCM/no-backup and Apple Keychain/CryptoKit AES-GCM/backup exclusion passed locally with key ID `tillfailure.recovery.v1`. Production rotation, secure erase, hardware backing and physical-device Data Protection remain unproved. |
| P1 | Can Rules-based `baseRevision` ordinary writes expose reliable acceptance/rejection and `lastOperationId` reconciliation after restart on both native SDKs? Engineering spike | 3/8 | If not, evaluate immutable server operation records; Milestone 8 stays blocked. |
| P1 | Can Apple/Android Storage uploads recover from process death with durable file references under platform sandbox rules? Engineering spike | 9/11 | Deferred to the first approved media/upload milestone; keep the safe pending-upload registry, copy approved media into app-owned pending storage and verify cleanup. Not a Milestone 3 blocker. |
| Resolved 2026-09-01 | Does Navigation 3 1.1.1 plus lifecycle 2.11.0-beta01 and Koin 4.2.2 compile and scope ViewModels correctly on the frozen toolchain? Engineering spike | 1 | Both targets compile/launch; Android pop/recreation passed and the user manually passed equivalent interactive behavior on an iPhone 16e simulator. Process-death/durable restoration remains out of scope and unverified. |
| Resolved 2026-09-01 | Keep frozen Compose 1.11.1 prerelease Material/lifecycle mappings or run a coordinated upgrade to newer stable versions? Engineering | 1 | Keep the existing Compose 1.11.1, Material 3 1.11.0-alpha07, and lifecycle 2.11.0-beta01 cluster for Milestone 1; no unrelated upgrade was made. Re-evaluate only as a coordinated future toolchain change. |
| P1 | Does the one-active-account sign-out protocol meet shared-device risk, or must Firestore persistence be disabled? Security/engineering | 3/4/12 | `clearPersistence` removes pending/cache only after termination and is not secure erase; cleanup failure blocks another account. |
| P1 | App Check rollout and attestation behavior for debug/emulator/unsupported devices? Security/product | 12 | Evaluate before production; never treat App Check as user authorization. |
| P2 | Template draft persistence mechanism? Engineering/product | 6 | Remains separate from the selected immutable assignment materialization; choose the smallest verified draft mechanism. |
| P2 | Analytics consent model, allowed event dictionary, crash collection defaults and regional controls? Legal/product | 12 | Minimal disabled-or-consented collection according to final policy; no private content. |
| P2 | CI provider, macOS/Xcode availability, physical Android/iOS device ownership, APNs signing, and release signing custody? Engineering/operations | 1/11/12 | Report missing platform checks; do not waive them. |

## Architecture selected for the proposal

- **Booking contention:** `workspaces/{workspaceId}/bookingSlots/{trainerId}_{utcBucket}` covers the complete outward-rounded buffered UTC interval. Trusted transactions acquire/change/release locks with the appointment and deterministic command receipt; querying an empty appointment range is not a lock. Quantum values/product timing remain open above; the shared-contention invariant does not.
- **Global catalog authorization:** `users/{uid}/authorizations/systemCatalog` is the fixed Rules-readable account entitlement, atomically maintained from membership/workspace lifecycle with account-status gating. Memberships remain authoritative for workspace-scoped roles/data. This does not use arbitrary membership lookup in Rules, delayed custom claims, or a client flag.
- **Assigned content authorization (2026-09-11):** `users/{uid}/workspaces/{wid}/assignedPrograms/{aid}/snapshots/content` is the immutable client-safe copy. Direct account/workspace/membership and parent-assignment checks gate reads; original trainer templates/versions/items are never client-readable. Trusted bounded transactions publish copy/plans/inventory/indexes/receipt together. New content or reassignment uses new identity; terminal/expired content cannot be reactivated by retry.
- Milestone 3 proved these selected access/transaction primitives in local emulators. Milestone 4 identity lifecycle integration, Milestone 7 assignment integration, and Milestone 10 scheduling implementation remain. ADR-001's native SDK/bridge boundary and encrypted local recovery direction are accepted. Concrete product caps, policy durations, production rotation/physical-device protection, and product-specific offline reconciliation remain open above.

## Design clarifications

| Priority | Question | Current treatment |
|---|---|---|
| P1 | Approve preserving visible 44-unit controls while enforcing 48 dp effective Compose hit targets? | Proposed accessibility reconciliation; no Pencil change made. |
| P1 | Responsive layouts across supported phones, landscape policy, dynamic type, long localization and reduced motion? | Use adaptive/scrolling layouts and test; static frames are not complete evidence. |
| P2 | Which settings/profile surfaces are essential at launch, and what should replace long-term business controls? | Include privacy/security/account essentials; omit post-MVP controls rather than disable them. |

## Non-blockers and explicit exclusions

Payments, invoices, subscriptions, session credits, group sessions, advanced analytics/photo comparison, multi-trainer workspaces, public trainer discovery, AI programming, wearables/nutrition, and desktop/web targets are not questions for the MVP plan. They remain deferred unless scope is explicitly reopened.
