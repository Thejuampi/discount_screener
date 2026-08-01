---
title: "Android Valuation Decision UX — Experience"
status: final
created: "2026-07-30"
updated: "2026-07-30"
sources:
  - "DESIGN.md"
  - "../../prds/prd-android-valuation-decision-ux-2026-07-30/prd.md"
---

# Foundation

Android phone, Compose Material 3, existing Detail tabs. Visual identity is `DESIGN.md`.

# Information Architecture

Lens: decision stack → source anchors/ranges → typed reasons → collapsed More evidence. Snapshot retains chart inspection and a compact Lens route. Estimates is aggregate model/Yahoo context. System configures TipRanks.

# Voice and Tone

Use factual labels: `Aligned`, `Tension`, `Disputed`, `Reference only`, `Fresh`, `Aging`, and `Stale`. Never say buy, sell, safe, likely winner, or consensus when providers disagree.

# Component and State Patterns

Each anchor exposes availability, coverage, freshness, confidence, and reason codes independently. Relation uses only decision-eligible comparable anchors. `More` is always available for non-primary Lens modules.

# Interaction Primitives

Load/refresh forecast and credential test require an explicit tap. A forecast action states its cost before dispatch. There are no automatic retries.

# Accessibility Floor

All state chips have text, controls are at least 48dp, source/range rows remain readable at narrow width, and collapsed evidence has an accessible expanded/collapsed state.

# Key Flow

Juan opens a detail, reads price and the three named anchors, sees whether their current comparable values align or dispute, expands More to inspect evidence, and explicitly loads TipRanks only when it can add decision value.

