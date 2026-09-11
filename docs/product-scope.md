# TillFailure product scope

Status: **proposal for review**
Product line: **TillFailure — Every rep counts.**
Planning date: **2026-09-01**

## Product outcome

TillFailure gives a personal trainer and invited clients one private workspace for programming, workout execution, progress, scheduling, and communication. It replaces chat threads, spreadsheets, and manually reconciled calendars without becoming a marketplace or practice-management suite in the first release.

## MVP users and journeys

| Role | MVP outcome |
|---|---|
| Client | Restore a session, accept an invitation, finish onboarding, see an assigned versioned plan, run and recover a workout offline, review history/progress, book an individual appointment, and message the trainer. |
| Trainer | Restore a session, invite/manage clients, curate exercises, build/version/assign programs, review logged workouts and progress, manage availability/appointments, keep private notes, and message clients. |

MVP includes authentication/session restoration; invitations/onboarding; role-aware shells; profiles/client management; system and custom exercises; program templates, versions, assignment, planned workouts; active workout/set logging, completion/history/review/feedback; basic progress entries; availability, blocked periods, individual booking/rescheduling/cancellation; one-to-one messaging, push notifications; essential offline/sync behavior; and privacy/account lifecycle.

## Explicitly deferred

- Payments, invoices, subscriptions, packages, and session credits.
- Group sessions and multiple trainers in one workspace.
- Public trainer discovery.
- Advanced analytics and advanced photo comparison.
- AI-generated programming, wearables, nutrition, and desktop/web apps.
- Automated business workflows shown as future concepts in the approved design.

Designed post-MVP controls stay absent or clearly unavailable through product copy; the MVP must not show tappable controls that do nothing.

## Product rules that drive architecture

- Workspace-scoped access requires current server account/workspace/membership authorization. Global system-catalog reads use the active account and fixed trusted entitlement defined in [firestore-security.md](firestore-security.md#global-catalog-rules-check); this grants no workspace role. A previously verified account may perform only the bounded, restricted local workout recovery proposed in [offline-sync.md](offline-sync.md); cached roles/entitlements never authorize backend access.
- A template edit creates a new template version. It never mutates assigned snapshots or completed workout history.
- Clients read only trusted, account-owned [assigned-program snapshots](firestore-schema.md#assigned-program-snapshot-identity) and derived planned workouts, never trainer templates/versions/items. Source IDs are audit metadata, not permission. Content changes/reassignment create a new immutable copy; cancellation/revocation/archive/expiry denies old content while unsynchronized cached work stays in locked account recovery under the offline policy.
- A planned workout describes intent; a workout session and its logged sets describe what actually occurred.
- Workout completion and other critical saved outcomes are durable records, not transient navigation/snackbar effects.
- Private trainer notes have a separate protected document boundary.
- A booking is confirmed only after trusted server code commits the appointment, deterministic buffered slot locks, and command receipt together, following the [booking protocol](firestore-security.md#booking-transaction-protocol).
- Firestore cache is not a guarantee that an unseen workout is available offline. The application explicitly preloads and verifies the next assigned workout.
- Health notes, progress data/photos, messages, tokens, and private media are sensitive. Analytics and logs contain identifiers/status codes only, never payload content.

## Release success criteria

- A client can complete a cached workout through process death and reconnect without losing acknowledged sets.
- Concurrent requests cannot double-book a trainer.
- Security-rule tests prove workspace, conversation, private-note, and media isolation.
- Trainer and client shells clear their back stacks and listeners when membership/session identity changes.
- Android and iOS render the shared approved design language with keyboard, screen-reader, 200% text, focus, reduced-motion, and offline/error states covered.

## Assumptions and unresolved scope

Assumptions: one trainer owns one workspace in MVP; trainer-client messaging is one-to-one; one appointment has one trainer and one client; metric/imperial is a presentation preference while canonical measurements use documented units.

Whether progress-photo capture itself is MVP (advanced comparison is not), which authentication providers launch, invitation expiry, account deletion/retention periods, and the booking cancellation policy remain unresolved in [open-questions.md](open-questions.md).
