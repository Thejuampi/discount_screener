# Changelog

## Unreleased

### V3.1 decision rule (Android core + headless)

- Act is now checked on `S_adj = S · exp(−1.5 · max(0,−T)/100) · exp(−0.3 · max(0,−m))` instead of
  the raw V3 composite S; `m` = clip(MACD histogram / close / 0.005, −1, 1) on the Year chart.
  Avoid logic and ranking (raw S) are unchanged. See `docs/architecture/decision-rule-v3-1.md`.
- Headless JSON rows carry `s_adj` and `m`; `/ranking` reports `rule_version: "V3.1"`.
  Android `ProjectedOpportunityRow` exposes `sAdj` and `macdMomentum`.
- JVM parity diff checks the V3.1 constants on both sides and compares `sAdj`/`m` (tol 1e-9).
