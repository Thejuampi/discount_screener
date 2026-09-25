---
title: Discovery list-density UX revision
status: implemented
date: 2026-07-14
author: Juan (delegated implementation)
related: Android Discovery tab
---

# Discovery list-density UX revision

## Problem

Discovery spent most of the viewport on a tall control card (“US Discovery” + counts + actions + filters). The ranked list was secondary and only ~2 rows were visible — inconsistent with **Opps** / **Upside**, where the list is the product and rows carry Act/Watch/Avoid + metric tokens.

## Principles

1. **List first** — when scores exist, ranked rows own remaining height.
2. **Chrome is a toolbar** — one summary line + text actions (`Score` / `Update` / `Filter` / `Cancel`), not a padded Card stack.
3. **Filters on demand** — collapsed by default; expand only when the user taps Filter.
4. **Same vocabulary as Opps** — Score badge, Act/Watch/Avoid (score cutoffs), F / T / Fc / Disc / Upside / Conf tokens, compact card padding (`8.dp` vertical, `6.dp` between cards).

## Layout

```
[ shared TopAppBar + search + tabs ]   ← app chrome (unchanged)
[ 142 ≥30 · 7,090 | Score | Update | Filter ]  ← ~1 row
[ optional thin progress when scoring ]
[ optional filter panel when expanded ]
[ LazyColumn of Discovery rows ……… ]  ← weight(1f)
```

Empty states (no list / not scored / filter too high) keep a centered empty panel with primary CTAs — no need for dense list chrome there.

## Out of scope

- Promote to tracked/watchlist
- Quant Lens chips on Discovery (no live book projection)
- Changing OpportunityEngine cutoffs
