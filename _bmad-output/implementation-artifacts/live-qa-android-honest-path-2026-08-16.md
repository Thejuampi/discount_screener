# Live QA — Android `qa` after identity CapEx / interest (2026-08-16)

**Surface:** Android emulator `discount_screener_api35` (`emulator-5554`)  
**Launch:** existing `make android-run-qa` process (chip **QA**, 20 names). No `pm clear`.  
**Profile:** `qa` (`apps/android/app/src/main/assets/profiles/qa.txt`)  
**Dumps:** `.agents/workspace/tmp/live-qa-android-2026-08-16/` (`detail-*.xml` / `.png`)  
**Windows:** not in this slice. Juan deferred the model port.

## Network note

The emulator started with `network speed` at **0 bit/s**. Yahoo host resolve failed until `adb emu network speed lte`. Keep that speed on for later remesure.

## Checklist

| # | Symbol | Result | Evidence |
| --- | --- | --- | --- |
| 1 | T | **PASS** | FCFF DCF $30.7. CapEx intensity **16.6%**. WACC 7.46% provisional. |
| 2 | AMZN | **PASS** | FCFF $182 / $438 / $623 (ordered). CapEx 18.4%. Tension, no single primary. |
| 3 | CI | **PASS** | Residual income $312. Healthcare Plans. CoE 6.60%. No FCFF primary. |
| 4 | UNH | **PASS** | Residual income $466. Healthcare Plans. CoE 7.54%. |
| 5 | JPM, ACGL | **PASS** | Residual $355 and $109. Banks / Insurance. CoE only. |
| 6 | MSFT (AAPL or MSFT) | **PASS** | FCFF $439 / $768 / $992 (ordered). CapEx 24.1%. |
| 6b | AAPL | **Remesured 2026-08-16 after period-end align** | SEC FCFF forms. Caveat is red on Detail. Street is primary because the fan is 12361 bps wide. |
| 7 | Garbage class | Not forced | — |

## Honesty UI

Every name with a computed identity shows **Mode: Honest** and a **Non-honest (Street-implied)** title plus knob notes. Working mode stays Honest.

## Rates

First pass used `rf 430bps · ERP 450bps bootstrap`. AAPL remesure uses live `rf 463bps · ERP 442bps implied_index · fred_dgs10`.

## Follow-up (not a checklist fail)

AAPL remesure after period-end align + red caveat (same `qa` process, no `pm clear`):

| Item | Live value |
| --- | --- |
| Class | Technology / Consumer Electronics |
| Source | `SecEdgar` |
| Identity base / bear / bull | $342.79 / $217.54 / $641.29 |
| Fan width | 12361 bps (cut is 12000) → `UnusableIdentityFan` |
| Primary | Analyst range $335.00 |
| Official gap | 230 bps |
| WACC | 6.13% · `rf 463bps · ERP 442bps implied_index · fred_dgs10` |
| Drivers | Normalized FCFF $118B · OCF 29.5% · CapEx 2.8% · years with coupon through 2023-09-30 |
| On-screen caveat | `Interest expense is missing for 2024-09-28, 2025-09-27. FCFF uses only years with a filed coupon.` |

SEC hunt for the missing coupon (FY2025 10-K accession `000032019325000079`, companyfacts, companyconcept `InterestExpense`):

| Place | What is filed |
| --- | --- |
| Income statement | Other income/(expense), net $(321)m / $269m / $(565)m. No interest-expense line. |
| Note 6 details | Other assets and other liabilities only. No coupon table. |
| Cash-flow supplemental | Cash paid for income taxes only. |
| Note 9 Debt | CP $8.0B at 4.19% (2025) and $10.0B at 5.00% (2024). Term-debt principal $91.3B with effective-rate ranges. |
| companyconcept `InterestExpense` | Last FY 10-K is 2023-09-30, $3.933B. No 2024-09-28 or 2025-09-27 row. 10-Q tags stop after FY2023 Q3. |

Do not invent a coupon from Other income or from a rate range × principal.
