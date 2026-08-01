---
title: "ADR: Android Valuation Decision UX and TipRanks Port"
status: final
date: "2026-07-30"
---

# ADR: Android Valuation Decision UX and TipRanks Port

## Context

Android must present independent valuation sources and port TipRanks without introducing hidden source blending, unsafe credential handling, or non-durable quota accounting.

## Decision

1. `core` owns a versioned valuation-decision policy. For valid same-currency/unit positive anchors, `differenceBps = floor((abs(a-b) * 20000 + floor((a+b)/2)) / (a+b))`; otherwise it returns no comparison and a reason code. Scenario order permits equality: `bear ≤ base ≤ bull`; `scenarioWidthBps = floor(((bull-bear) * 10000 + floor(base/2)) / base)` for positive base.
2. Relation reduces all eligible comparable pairs: zero `Unavailable`; one `SingleSource`; all `≤2500` `Aligned`; any `2501–5000` and none larger `Tension`; any `>5000` `Disputed`. Only an aligned set names an anchor: solid model, then Yahoo, then TipRanks. Foreign-currency anchors are visible `ReferenceOnly`, never comparison inputs.
3. Sources expose independent `Availability` (`Available`, `ReferenceOnly`, `Unavailable`), `Coverage` (`Sufficient`, `Sparse`, `Unknown`), freshness, confidence, and reason codes. TipRanks is visible with one positive observation, but needs three distinct identities no older than 90 days and cache no older than 7 days to become decision-eligible. No cross-provider or synthetic analyst aggregate exists.
4. Android uses its own `SQLiteStateStore` schema, Keystore AES-GCM envelope `{formatVersion,keyAlias,iv,ciphertext}`, cache, request ledger, and usage snapshots; the preferences file is backup-excluded. Equivalence is enforced by shared versioned goldens executed by Android and Windows.
5. A request is persisted `reserved` before dispatch and `sent` at dispatch. Only `reserved` may be cancelled. If UTC month changes before dispatch, the reservation is cancelled and recreated atomically. Orphans are conservatively charged to their dispatch month and are never released automatically without request-level provider evidence.
6. Cache read, credential test, forecast load/refresh, and usage reconciliation are separate explicit operations and all use the rate gate. Only a sent forecast load consumes the monthly 50 forecast-call budget. Local cooldown before dispatch is free; a sent `429` consumes its attempt and persists its `retry_after` or 60-second fallback. Automatic retries are forbidden.

## Consequences

The policy becomes testable and cache-invalidating. Forecast cache and accounting writes are independent. Reconciliation uses the last provider usage snapshot plus sent local attempts after that snapshot plus unresolved sent/orphan attempts; a reconciliation failure can never increase availability. Credential removal deletes both envelope and Keystore alias, invalidates the in-memory session, and cancels only undispatched requests; public cache remains available.

## Rejected alternatives

Presenter-owned policy, a cross-provider analyst average, Room migration, Windows-schema copying, automatic retries, and deleting public cache with credentials are rejected.

## Status

Accepted.
