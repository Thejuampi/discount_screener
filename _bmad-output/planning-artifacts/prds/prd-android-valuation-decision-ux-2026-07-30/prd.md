---
title: "PRD: Android Valuation Decision UX and TipRanks Port"
status: final
created: "2026-07-30"
updated: "2026-07-30"
sources:
  - "../android-quant-lens-prd-2026-05-03.md"
  - "../valuation-model-family-architecture.md"
---

# Android Valuation Decision UX and TipRanks Port

## Goal

Let Juan inspect a symbol's valuation decision on Android without confusing a model, Yahoo analyst targets, and TipRanks observations for one synthetic target. The existing Lens, Snapshot, Estimates, and System surfaces remain the only surfaces.

## Requirements

- **FR-1 Decision stack:** Lens shall render typed model, Yahoo, and TipRanks anchors, their ranges, provenance, and a typed relation. It shall never calculate a relation or select an anchor in Compose.
- **FR-2 Relation:** The core policy shall emit `Unavailable`, `SingleSource`, `Aligned`, `Tension`, or `Disputed` from decision-eligible comparable anchors. `Tension` and `Disputed` shall not name a primary anchor.
- **FR-3 Source integrity:** Yahoo and TipRanks keep their own consensus. The product shall not create a cross-provider analyst consensus or blend either provider with the model.
- **FR-4 Degraded truth:** Availability, coverage, freshness, confidence, and reason codes are independent. Stale and reference-only anchors remain inspectable; unavailable data is not rendered as zero.
- **FR-5 TipRanks operation:** Cache reads are free and local. Load/refresh is an explicit per-symbol action; no Android background, list, or index request may invoke the forecast endpoint.
- **FR-6 Credential and quota:** API keys remain Keystore-protected and excluded from backup. The durable request ledger conservatively charges sent/orphan requests and preserves public cache after key removal.
- **FR-7 Existing surfaces:** Snapshot links compactly to Lens; Estimates remains model/Yahoo portfolio context; System owns TipRanks configuration and quota. Lower-signal Lens modules are collapsed under More, not removed.

## Acceptance Criteria

- Given two comparable eligible anchors differ by 2,500 bps, when the policy runs, then their relation is `Aligned`; at 2,501 bps it is `Tension`; at 5,001 bps it is `Disputed`.
- Given model, Yahoo, and TipRanks are eligible, when all comparable pairs are aligned, then a solid model is primary; otherwise Yahoo is primary before TipRanks.
- Given TipRanks has fewer than three current/aging unique identities, when rendered, then it is reference-only and cannot determine relation or primary anchor.
- Given a provider request has been sent and the app dies, when restarted, then its ledger entry remains charged until request-level provider evidence resolves it.
- Given a stale source conflicts with current anchors, when rendered, then it remains visible as reference-only and does not alter the current relation.
- Given a user loads the Estimates tab or a list, when no explicit forecast action occurred, then no TipRanks forecast endpoint call occurs.

