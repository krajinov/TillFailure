# ADR-002: private-note settlement in the existing command envelope

Status: accepted for the synthetic emulator slice by Josip's N1–N5 approval, 2026-10-24.

## Context

A submitted note must survive an unknown outcome, process loss and loss of workspace authority. The existing one-command ledger already supplies durable preparation, own-command resolution, whole-key fencing and proof-before-retirement. Its historical last-settled proof retains the original request, which would retain note text after settlement.

## Decision

Keep the existing UID envelope, single pending slot and last-settled slot. Pending note commands contain their exact body, random binding nonce, target, expected revisions and original key. Normal or resolver settlement first durably writes the fully validated terminal proof with that pending command. Only after that write does retirement compact the note into a tagged `compacted-v1` proof containing a schema-1 `SettledCommandMarker` with UID, command kind/ID, hash format/hash, source and outcome; a resolver source additionally retains its validated decision time. Normal acknowledgments invent no server decision timestamp.

The retired marker has no original command, note text, nonce, raw key, client or workspace selector. Its authority comes from the prior verified local transition and authenticated encrypted envelope, not a network-provided assertion or recomputation of discarded text. It never grants a role or note access. Same caller/key is rejected by its derived command ID. A compact marker cannot stand in for pending proof, coexist with pending work using the same key, or be accepted in another UID's envelope.

All seven previous commands keep their full legacy proof form and existing hashes. The upgraded decoder accepts those records; unsupported older decoders must lock on new fields/forms instead of discarding work. Restart can finish retirement from an intact full pending proof. Storage/key/version ambiguity remains locked, with no reset, replacement or discard.

Use one new typed Save adapter in this protocol and one guarded point read/Save mediator. Direct Firestore note access remains denied. This decision creates no second queue/store/coordinator and no generic ordinary-write recovery claim. Unsent drafts are memory-only; pending bodies have the existing native encryption/backup protections and can remain unresolved when identity/storage is unavailable. This is not secure-erasure or real-data retention approval.

## Consequences and proof

The envelope/ledger, transient acceptance consumer, recovery presentation and both native transports understand the tagged retired form. The original seven variants remain regression-tested. The [PR 5 report](milestones/milestone-5-pr5-report.md) attributes prerequisite tests, final suites, real native process recovery and interaction limitations separately. Production, real personal data, offline grants, privileged support, deletion and historical restoration remain outside this decision.


## Custom-exercise extension (C6 approved 2026-10-26)

Josip approved the [M6 PR 2 contract](milestones/milestone-6-pr2-plan.md#approval-and-implementation-authorization-2026-10-26). `custom-exercise-create` and `custom-exercise-correct` now use the same exact-command pending slot, full durable terminal proof and `compacted-v1` retirement. Their marker retains only UID, kind/command ID, `custom-exercise-v1` hash/version, source/outcome and a resolver decision time when actually supplied. It discards exercise/workspace IDs, authority, name, instructions, taxonomy and raw key only after verified proof persistence. Unknown outcomes retain the original encrypted submitted command. No original text is silently retained in the settled slot.

The eight earlier variants keep their original hashes, receipt forms and recovery behavior: the seven legacy forms remain full proofs, and trainer notes retain their existing compaction and permanent relationship-binding policy. Custom definitions instead retain immutable author/workspace identity and check current authority; command authority revisions are immutable submission bindings, not permanently occupied client relationships. This extension creates no queue, store, offline grant, real-data retention or restoration operation. The new command's complete admission/proof/encrypted budgets and evidence are specified in the [PR 2 plan](milestones/milestone-6-pr2-plan.md#pending-budgets-and-settled-marker-retention) and [report](milestones/milestone-6-pr2-report.md).

## Program-draft extension (P6 approved 2026-10-28)

The [P1–P6/P7 approval](milestones/milestone-6-pr3-plan.md#approval-and-implementation-authorization-2026-10-28) extends this same protocol to `program-draft-save` / `program-draft-v1`. Full original draft content, source tuples, authority and key remain encrypted in the existing pending slot until matching full proof is durably written. Normal saved, draft-conflict and source-conflict decisions, or exact own resolution, then retire into the existing content-free marker. The marker contains no template/workspace/item/source identity or content; normal proofs invent no decision time. The seven legacy full-proof variants and note/custom markers retain their existing forms. Older unsupported decoders must lock rather than remove fields or work.

Draft admission serializes actual prior history plus every prospective preparation, resolution, proof and retirement state. The new-kind plaintext ceiling is 36,500 bytes under the existing 64 KiB envelope and 96 KiB encrypted budgets. Swift slash escaping is included in a deterministic bound derived from the production wrapper schema; native encryption measurements supplement that bound. Pending draft work also prevents workspace-preference metadata changes from consuming reserved proof space. This does not change inherited custom-command admission: its separately measured counterexample/bound and proposed correction are recorded in the [PR 3 report](milestones/milestone-6-pr3-report.md). No history is cleared, encoding changed, second store added, or production/offline policy enabled.
