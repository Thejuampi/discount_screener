# Fix: AMZN model understatement vs market/street (2026-08-01)

## User challenge

- Market ≈ **$272**, Street / thesis ≈ **$313** PT (e.g. ~28× 2028E EPS).
- Model emitting **$50–60** is not a “disputed soft” nuance — it is a **broken economic model** for a CapEx-wave compounder.

## Diagnosis

| Piece | Live before /15 | Implication |
| --- | --- | --- |
| Price | $271.58 | |
| FCFF base | **$50.30** | ~0.18× market |
| Run-rate | ~$39B | OCF ~$140B, gross CapEx ~$132B |
| Path | Full `OCF − CapEx` median | Charges **growth** CapEx (AWS/AI) as if maintenance |
| Horizon | 5y fade to g=3% | Double-counts: high CapEx **and** growth in the path |
| Street method | EPS × multiple | Different instrument — not pure FCFF |

Aritmética del $50 era “coherente” con $39B FCFF @ ~10% WACC; la **definición de owner earnings** no lo era.

## Policy /15 — owner earnings under investment waves

`business-class-policy/15-owner-earnings-maintenance-capex`

> **Handoff note (2026-08-02):** This OE path is intentional for AMZN-class investment waves. It was also the root of the CHTR FCFF/sh ~$141 sniff failure (maintenance CapEx capped too low for structural network CapEx).
>
> **Superseded 2026-08-02 by policy/16 `growth-earned-sustaining-capex`.** Rule 2 below no longer applies: maintenance is now `κ × δ/(δ+g)` (capital intensity × asset renewal share), **not** `min(CapEx p25, 15% of OCF margin)`. Growth CapEx must be earned by revenue growth, so the AMZN OE path survives (live run-rate $65.7B, maintenance 595 bps) while flat-growth networks no longer qualify as investment waves. AMZN's model value legitimately falls — the ~2.0% maintenance floor here was below any defensible renewal on a ~$300B asset base with ~$60B annual D&A. The live-after-fix table below is policy/15 and is kept as history. See [`handover-quant-valuation-engine-2026-08-02.md`](handover-quant-valuation-engine-2026-08-02.md) §12.

1. **Scenarios** still use annual FCFF identities (negatives retained — MU).
2. **Base** when investment wave (CapEx spikes / CapEx ≫ maintenance):
   - `owner_margin = OCF_margin + after-tax interest − maintenance_capex`
   - `maintenance = min(historical CapEx p25, 15% of OCF margin)`, floor 2% of sales
   - Used only if **higher** than non-neg annual FCFF median (never shrinks healthy FCFF).
3. **Secular / owner path**: 10-year explicit horizon, fade exponent ≥ 1.5.
4. Provenance: `fcff_margin=owner_earnings_ocf_minus_maintenance`, `capex=maintenance_intensity_bps`, `projection_years=10`.

## Live after fix (qa, same session)

| | Value |
| --- | --- |
| Market | $271.58 |
| **Model base** | **$167.20** |
| Bear / bull | $77.76 / $171.42 |
| Run-rate | ~$92.6B |
| Margin | 1291 bps owner earnings |
| Maintenance CapEx | 221 bps of sales |
| Regime | secular_expansion, 10y |

## What this is **not**

- Not a clamp to price or Street PT.
- Not a PE model (28× EPS). Getting **exactly** $313 would require changing instrument (earnings capitalization), not only CapEx treatment.
- Residual gap vs market (~0.6×) can still show **disputed** vs analyst anchors — that is honest if anchors disagree.

## Offline

- `cargo test --lib dcf_model::` / `valuation_baseline::` green.
- Android `DcfAnalysisEngine` parity for policy /15.
