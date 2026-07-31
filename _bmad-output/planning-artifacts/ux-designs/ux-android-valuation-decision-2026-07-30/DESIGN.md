---
title: "Android Valuation Decision UX — Design"
status: final
created: "2026-07-30"
updated: "2026-07-30"
colors: "inherit Material 3 DiscountScreenerTheme"
typography: "inherit Material 3"
rounded: "inherit theme shapes"
spacing: "8dp operational rhythm"
components: "existing Compose Material 3 components"
---

# Brand & Style

Dense, inspectable financial workstation UI for a single self-directed analyst. Evidence outranks decoration; no recommendation language, synthetic targets, or tutorial copy.

# Colors

Use existing semantic Material 3 colors only. Relation, freshness, and confidence are separate labelled tokens; color never carries meaning alone.

# Typography

The value line is prominent, source/range line secondary, and provenance/reason text tertiary. Numeric values use stable-width formatting where available.

# Layout & Spacing

Lens starts with one decision stack and expands evidence vertically. Use 8dp rhythm, no nested decorative cards, and preserve one vertical scroll.

# Components

`AnchorRow`, `RelationChip`, `SourceStateRow`, and `More` reuse existing cards/chips. They expose text labels for all semantic states.

# Do's and Don'ts

Do show every source separately and surface disagreement. Do not blend sources, promote stale values, hide refusal reasons, or add a new navigation surface.

