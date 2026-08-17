# Live QA — Android `qa` forecast-first Detail (2026-08-16)

**Surface:** Android emulator `discount_screener_api35` (`emulator-5554`)  
**Launch:** prior `make android-run-qa` process. Chip **QA**. **Upside 20**. No `pm clear`.  
**Profile:** `qa` (`apps/android/app/src/main/assets/profiles/qa.txt`)  
**Dumps:** `.agents/workspace/tmp/live-qa-android-forecast-2026-08-16/` and `.agents/workspace/tmp/live-qa-android-forecast-ux-rerun/`  
**Windows:** closed.

## Bar

Snapshot leads with **Forecast** = Price + Analyst range. Identity dollars sit under **Model**. No `Our price` on Snapshot.

## Checklist

| # | Symbol | Result | Evidence |
| --- | --- | --- | --- |
| 1 | T | **PASS** | First paint: Price $24.89, Analyst $28.00, +12.49% vs price. Model Honest $26.91, Non-honest $28.00. Bends near-term growth **-2.59% → -1.42%**. WACC 6.05%. Normalized FCFF $25.1B. |
| 2 | AMZN | **PASS** | First paint: Price $262.65, Analyst $325.00. Model Honest $329.41, Non-honest $329.41. WACC 10.26%. Normalized FCFF $95.5B. |
| 3 | CI | **PASS** | First paint: Price $282.56, Analyst $343.00. Model Honest $328.07, Non-honest $343.35. Bends discount rate **6.89% → 6.62%**. CoE 6.89%. Starting ROE path. No FCFF primary. |
| 4 | UNH | **PASS** | First paint: Price $401.73, Analyst $490.00. Model Honest $488.24, Non-honest $488.24. Starting ROE path. Identity vs analyst 36 bps. |
| 5 | JPM | **PASS** | First paint: Price $362.84, Analyst $372.00. Model Honest $367.88, Non-honest $367.88. Starting ROE + cost of equity 814 bps. |
| 6 | AAPL | **PASS** | First paint: Price $305.93, Analyst $335.00. Model Honest $342.06, Non-honest $335.00. Bends stable cash margin **28.23% → 27.65%**. Lens chip **Analyst consensus**, −29% / +5% / +30%. |
| 7 | Garbage class | Not forced | — |

## Forecast copy

Every name printed **Forecast is the analyst range.** Snapshot headline is `Price $X  Analyst $Y`.

## Script note

The batched swipe (7 short flicks) stopped on AAPL at the Model header. Caveats push Honest below the fold. A follow-up swipe printed Honest $342.06 and Non-honest $335.00. Product pass. Script miss.

## Honesty pair

Working number is Honest on every priced name.

| Name | Honest | Non-honest | Why Non-honest |
| --- | --- | --- | --- |
| T | $26.91 | $28.00 | Bends near-term growth -2.59% to -1.42%. |
| AMZN | $329.41 | $329.41 | Honest and Street already sit together. |
| CI | $328.07 | $343.35 | Bends the discount rate from 6.89% to 6.62%. |
| UNH | $488.24 | $488.24 | Honest and Street already sit together. |
| JPM | $367.88 | $367.88 | Honest and Street already sit together. |
| AAPL | $342.06 | $335.00 | Bends the stable cash margin from 28.23% to 27.65%. |
