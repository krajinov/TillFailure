# Design inventory

Status: **verified design evidence and proposed product mapping**
Inspection date: **2026-09-01**
Source: approved `design/tillfailure.pen`, inspected with the supported Pencil tools. The file was not modified.

## Evidence and interpretation rules

- The file contains 150 top-level frames and 30 reusable component masters. The canonical overview frames are `V95tp` (foundations), `VJuJK` (components), `OmHDs`/`JreTn`/`v66zG` (client examples), and `eq0D5`/`bLqqR`/`G7StO` (trainer examples).
- A frame is a visual state, not automatically a navigation destination or ViewModel. Dialogs, sheets, focus/error states, empty states, and transitions stay owned by their parent feature unless independent deep linking or lifecycle requires otherwise.
- “MVP” below means the design can inform an MVP workflow. It does not authorize implementation, nor does it require every visual state to ship.
- Post-MVP controls will be omitted or clearly unavailable in the initial release; nonfunctional affordances will not be exposed.

## Section-to-product map

| Design section and node identifiers | Role | Navigation destination / overlay | Reusable language or visual states | Logical feature | Scope |
|---|---|---|---|---|---|
| Cover `bi8Au`; foundations `V95tp`; components `VJuJK` | Both | Not runtime destinations | Tokens and component masters | Design system | MVP foundation |
| Splash `w8JPk`; welcome `CNVXk`; sign-in `QVhze`; forgot password `VLQIa`; invitation `gIKd5`; create password `g45ymn` | Both | Splash, Auth, Invitation | Loading, validation, link handling | Auth and invitations | MVP |
| Personal info `YLmfR`; goal `I0OPR9`; experience `wKnk0`; injuries `C6P6r`; completion `Z03aYo` | Client | Onboarding flow | Multi-step form states | Client onboarding | MVP; collect only necessary health data |
| Home `OmHDs` | Client | Client Home | Workout/metric cards | Client shell | MVP |
| Workout list/detail/history `PgQIz`, `u7elNE`, `kCQVL`, `E7rGux`, `ul1mR`, `Pz4SR`, `jhUhB`; canonical detail `JreTn` | Client | Workouts, Workout detail, History | Filters, workout cards, empty/detail states | Assigned programs and history | MVP |
| Active-workout family `jhf5Q`, `C5HODY`, `l3S05W`, `nDFQc`, `L8DQmp`, `fgCMP`, `LG4He`, `sOYZX`, `nar0t`, `E8RN5`, `K01ZjI`, `x5it13`; canonical `v66zG` | Client | Active Workout; completion summary | Current/completed/edited/incomplete/skipped set states, exercise transition, finish confirmation | Workout logging and recovery | MVP |
| Schedule `Qzw3v`, `uRHL9`, `daJl4`, `Y2t1JX`, `Fvzuc`, `Uixpb`, `GXn84`, `c8jqQ8` | Client | Schedule, Appointment detail | Booking/reschedule/cancel sheets and confirmation states | Appointments | MVP |
| Progress `S50yVn`, `YUV0C`, `X1Usd5`, `cBs7m`, `d2N0H`, `k9DQJu`, `jfwOv`; comparison `HZ3BE` | Client | Progress, Add entry | Charts, metric/photo entry states | Basic progress | MVP except advanced photo comparison `HZ3BE` is post-MVP |
| Messaging `fpBXL`, `fUtbx`, `HzdYn`, `lJG8L`, `wsDxj`, `o09OmK` | Client | Conversations, Conversation | Composer, attachment and notification states | Messaging | MVP |
| Profile/settings `jAfDj`, `Zplyt`, `hGFgW`, `aKhM8`, `zabof`, `LzGYK`, `dDMyD`, `uVsl3`, `ogLZl`, `Uvlvs` | Client | Profile and settings destinations | Forms, confirmations | Profile, privacy, account lifecycle | Essential subset MVP |
| System states `ToGw2`, `gR8dX`, `eiNFB`; prototype map `d2ARY` | Client | Parent-owned states; map is documentation | Offline, error, empty/system feedback | Cross-cutting resilience | MVP |
| Trainer catalog `d1PGi`; dashboard `ePtrS`, `Y1551L`, `uftsn`, `D6TXf3`, `uupAB`, `acq8f`, `X3Lv6`, `M5so7H`, `ih2kd`, `XQ6UY`, `CqzMi`; canonical `eq0D5` | Trainer | Trainer Home | Metrics, review queues, empty/loading states | Trainer shell and review | MVP |
| Clients `FOi6s`, `Ges7z`, `bC9kS`, `cP5nt`, `hXxfx`, `c42p8q`, `kOslh`, `ZersA`, `ZJDMu`, `R87tv3`, `b6YP4`, `Q9g7KF`, `Hzv9G`; canonical detail `bLqqR` | Trainer | Clients, Client detail, Invite | Client cards, filters, invite sheets | Client management | MVP |
| Programs `R1fmB`, `ESl4e`, `TSSam`, `K2ved`, `ZpoAR`, `zQwo8`, `ebdKd`, `r9dwqa`, `fWI3J`, `sAv0c`, `W1Imbf`, `ziDYW`, `HNGLy`, `mGklx`; canonical builder `G7StO` | Trainer | Programs, Template detail, Builder, Assignment | Builder rows, pickers, validation and assignment overlays | Programming | MVP |
| Exercises `LDmjm`, `Easmv`, `PdFjC`, `GoLR8`, `qRujb`, `mr1jb` | Trainer | Exercise library/detail/editor | Search/filter, system/custom badges | Exercise library | MVP |
| Schedule `T9XLZ`, `H25Rj`, `VL0HV`, `Bc74c`, `z3t5G`, `t6L5r`, `c7JB5`, `jKAUE`, `NJnFu` | Trainer | Schedule, Availability, Appointment | Availability editor, booking/detail overlays | Scheduling | MVP |
| Messaging/notifications `KVlI6`, `FohuA`, `FEnFb`, `d3lt4w`, `j3F2j`, `j6DzK7`, `NinrH`, `c91ZrG` | Trainer | Conversations, Conversation, Notifications | Composer and notification states | Messaging and notification inbox | MVP |
| Profile/settings `RanGd`, `fQfOZ`, `d1g57`, `NW1fN`, `kNbWr`, `cMskj`, `D7VFC`; business controls `CIb9A` | Trainer | Profile and settings destinations | Forms, confirmations | Profile/privacy; business administration | Essential settings MVP; business/payments `CIb9A` post-MVP |
| System states `BJxtv`, `JAwpk`, `Zu1sI`; prototype map `f2ihUP`; design audit `NiTS6` | Trainer | Parent-owned states; maps/audit are documentation | Offline, error, empty/system feedback | Cross-cutting resilience | MVP |

## Reusable component masters

| Family | Node identifiers | Planned shared component role |
|---|---|---|
| Buttons | `NU7jw`, `X8gE9`, `ZFMJn`, `fGtci`, `O3cnXe`, `Ji0PJ`, `oODyW`, `q7eCZE`, `NFnk8`, `v1XI8j`, `Ssdvr` | Primary, secondary, destructive, icon, text and floating actions plus interaction states |
| Inputs and filters | `aE0zi`, `D13Frs`, `J9Rg26`, `R57Ctu`, `SSCsC` | Text input, search, filter chip, error and focused states |
| Status and feedback | `GWeKF`, `Ror2j` | Status chip and success snackbar |
| Cards and rows | `wPBlW`, `JBItX`, `CqAPU`, `WBPeb` | Workout, client and metric cards; logged-set row |
| Navigation | `zG1eo`, `rEyCa` | Top app bar and role-specific bottom bar shell |
| Workout states | `kSm2M`, `WZZ4R`, `hO3GP`, `JMEZN`, `fP5DA`, `v5mKS` | Current/completed/edited/incomplete/skipped set and exercise transition |

## Verified visual foundations

| Token family | Approved values |
|---|---|
| Color | background `#111312`; surface `#191C1A`; surface-2 `#222622`; border `#30352F`; primary text `#F5F7F2`; secondary text `#9DA49C`; electric-lime accent `#C8F04B`; accent ink `#172000`; success `#55D68B`; warning `#F5B84B`; error `#FF6B68`; info `#6EAAFF`; focus `#D8FF66` |
| Typography | Manrope display; Inter body |
| Spacing | 4, 8, 12, 16, 20, 24, 32 |
| Radius | 6, 10, 16, 24 |
| Designed control target | 44 dp/pt |

The shared design system will encode semantic tokens and component APIs rather than repeating literals in feature screens. Dark surfaces, lime accent, type hierarchy, spacing, radii, and component language are approved constraints.

## Accessibility and design approval boundary

The Compose skill requires at least 48×48 dp effective touch targets. The approved design records 44. The implementation proposal is to preserve the visible 44 geometry while applying a 48 dp effective interactive target and accessible semantics. Any visible token change remains a design decision and requires approval. Forms must support screen readers, focus order, keyboard/IME behavior, dynamic type/font scaling, sufficient contrast, and error text that is not color-only.

## Design evidence still missing

- Exact responsive behavior at every supported device size is not fully specified by static frames.
- Localization expansion, reduced-motion behavior, and complete screen-reader labels need product/design review.
- Whether profile photos, progress photos, and message attachments are required at MVP launch needs confirmation.
- The long-term design includes revenue and advanced comparison surfaces that are explicitly outside the MVP.
