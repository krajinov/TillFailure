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

- Every server request requires a current server-authorized membership. A previously verified account may perform only the proposed bounded, restricted local workout recovery described in `offline-sync.md`; an offline device cannot immediately discover revocation, and a cached role/grant never authorizes backend access.
- A template edit creates a new template version. It never mutates assigned snapshots or completed workout history.
- A planned workout describes intent; a workout session and its logged sets describe what actually occurred.
- Workout completion and other critical saved outcomes are durable records, not transient navigation/snackbar effects.
- Private trainer notes have a separate protected document boundary.
- A booking is not confirmed until trusted server code has checked availability transactionally.
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
