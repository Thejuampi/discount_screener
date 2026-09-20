# Live QA — Android `qa` after debt engine / issuer yield (2026-08-16)

**Surface:** Android emulator `discount_screener_api35` (`emulator-5554`)  
**Launch:** `make android-run-qa`. Chip **QA**. **Upside 20**. No `pm clear`.  
**Profile:** `qa` (`apps/android/app/src/main/assets/profiles/qa.txt`)  
**Dumps:** `.agents/workspace/tmp/live-qa-android-2026-08-16b/`  
**Windows:** closed. Juan deferred the port.

## Cache note

The first install still showed T as **coverage synthetic**. On-device DCF lacked `issuer_yield=issuer-market-yield/2`. `isCurrentPolicy` now requires that stamp on every FCFF run. The second install recomputed. No `pm clear`.

## Checklist

| # | Symbol | Result | Evidence |
| --- | --- | --- | --- |
| 1 | T | **PASS** | FCFF DCF $26.91. Yield **602 bps**. WACC 6.05%. Identity, Aligned. Official gap 397 bps. |
| 2 | AMZN | **PASS** | FCFF $329.41. Range $230–$405 (ordered). Yield **505 bps**. WACC 10.26%. |
| 3 | CI | **PASS** | Residual income $328.07. Healthcare Plans. CoE 6.89%. No FCFF. No k_d line. |
| 4 | UNH | **PASS** | Residual income $488.24. Healthcare Plans. CoE 7.81%. No FCFF. |
| 5 | JPM | **PASS** | Residual income $367.88. Banks - Diversified. CoE 8.14%. No FCFF. |
| 6 | AAPL | **PASS** | Identity $342.06. Yield **471 bps**. Fan unusable. Street $335 primary. Official gap 209 bps. Coupon estimate Medium for 2024-09-28, 2025-09-27. |
| 7 | Garbage class | Not forced | — |

## Debt engine on screen

| Name | k_d line |
| --- | --- |
| T | Cost of debt is the current instrument yield, 602 bps. |
| AMZN | Cost of debt is the current instrument yield, 505 bps. |
| AAPL | Cost of debt is the current instrument yield, 471 bps. |
| CI, UNH, JPM | No k_d line. Residual income uses CoE only. |

Debt stock line is on every operating name: **Debt stock is the filed year-end instant.**

## Honesty

Every priced name shows **Mode: Honest**. Non-honest Street-implied knobs stay labeled.
