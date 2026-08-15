# Analyst-method lifecycle closure

Applies to evidence-ledger, analyst-import, model-run, or current-projection work.

Read this file before you implement or close those slices. A green builder handoff is not independent closure.

## Three states

Keep these distinct: **design-ready**, **implemented**, and **independently closed**.

A green builder handoff may establish the second state. It cannot establish the third by itself.

Before the next slice may expose or consume the result, an independent adversarial checkpoint must trace the complete command from control-envelope admission through semantic admission, persistence, invalidation, restart, and read projection.

## Reserved claims

| Claim | Required proof |
| --- | --- |
| `evidence-bound` | Values come from frozen observation IDs whose metric role, basis, period/horizon, currency/share basis, and lineage are compatible—not merely from the right numeric unit. |
| `atomic` | The entire business transition is one transaction: publish the replacement **or** append the refusal/invalidation and clear stale current state. A helper or inner DB commit is insufficient. |
| `idempotent` | Exact retry compares the full semantic command: role bindings, issuer/security/identity vintage, evidence set, method/engine/policy, replay mode, stable decision instant, projection key, and supersession intent. Per-attempt processing time is separate; equal output alone is insufficient. |
| `reconstructible` | Explicit persisted fields and immutable membership can rebuild the command, result, supersession, and complete reachable revision ancestry. An opaque fingerprint, mutable “current identity” lookup, or immediate-parent-only check is insufficient. |
| `dual-lock` | Both platforms execute the same shared negative and positive fixtures. A contract field silently ignored by both readers is not coverage. |
| `fail-closed` | Counterexamples prove refusal **and** the required state effect: no partial writes, no foreign invalidation, no stale projection after a trusted rejected revision. |

## Slice closure

Slice closure requires:

- a named invariant/attack matrix
- negative mutations for every semantic fingerprint field
- a mid-transaction rollback/reopen test
- reconciliation of plan/checkpoint status with the code and gates

Treat green tests as evidence for the encoded properties, never as the conclusion that all required properties were encoded.

Failure-mode rows that already bit this path live in [`operational-anti-patterns.md`](operational-anti-patterns.md).
