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
