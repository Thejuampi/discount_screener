# Test report — Android Detail session cache

**Standard:** use-case test (Sommerville) + UML scenario template  
**Session:** 2026-08-16  
**System:** Discount Screener Android debug  
**Build:** `make android-run-qa` (`-PdsQaUniverse=true`)  
**Actor:** analyst (Juan)  
**Tester:** agent on emulator  
**Evidence:** `.agents/workspace/tmp/live-qa-android-cache-2026-08-16/`

## 1. Environment

| Item | Value |
| --- | --- |
| Device | `emulator-5554` · `discount_screener_api35` · API 15 |
| Network | download 173 Mbit/s · upload 58 Mbit/s |
| App data | kept (no `pm clear`) |
| Universe lock | profile `qa` · pin `apps/android/app/src/main/assets/profiles/qa.txt` |
| Out of scope | Windows · valuation six-name checklist · `sp500` |

## 2. Summary

| Use case | Scenarios run | Pass | Fail | Not run |
| --- | --- | --- | --- | --- |
| UC-1 Boot QA universe | 1 | 1 | 0 | 0 |
| UC-2 Review leftover board | 1 | 1 | 0 | 0 |
| UC-3 Open ticker from leftover | 2 (first JPM, reopen JPM) | 2 | 0 | 0 |
| UC-4 Close Detail | 2 (from JPM, from EXPD) | 2 | 0 | 0 |
| UC-5 One-shot search ticker | 2 (cold EXPD, warm EXPD) | 2 | 0 | 0 |
| UC-6 Valuation six-name live path | 0 | — | — | 6 names |

**Session verdict:** all executed scenarios passed. Valuation live path was not in this session.

## 3. Use cases

### UC-1 Boot the QA universe

| Field | Content |
| --- | --- |
| Actor | Analyst |
| Goal | Start the workstation on the locked `qa` sample |
| Preconditions | Emulator is online. App data from prior runs remains. |
| Main success scenario | 1. Install debug APK with QA flag. 2. Launch `MainActivity`. 3. Read home. |
| Expected | Chip shows QA. Tracked count is ≤20. Database is not wiped. |
| Actual | Chip **QA**. Tab **Upside 20**. Opps 2. Home lists NVDA and META. |
| Postcondition | Profile membership is the QA pin. |
| Status | **Pass** |
| Evidence | `home.png` / `home.xml` |

### UC-2 Review the leftover board

| Field | Content |
| --- | --- |
| Actor | Analyst |
| Goal | See leftover fade names on the current universe |
| Preconditions | UC-1 passed. |
| Main success scenario | 1. Select **Plans**. 2. Select **Leftover**. 3. Read board counts and cards. |
| Expected | Board scans the QA universe. Fade and at-target lanes are explicit. |
| Actual | **Universe qa · 20 scanned**. **0 fade · 2 at target · 18 out**. Primary empty: "No leftover fade". At target: **JPM**, **MRK**. |
| Postcondition | Leftover board is visible. Detail is closed. |
| Status | **Pass** |
| Evidence | `leftover.png` / `leftover.xml` |

### UC-3 Open a ticker from leftover

**UC-3a — first open of JPM (main success)**

| Field | Content |
| --- | --- |
| Preconditions | UC-2. JPM card is on the at-target lane. |
| Steps | 1. Tap **JPM**. 2. Capture Detail. |
| Expected | Detail route is JPM. Price, forecast, and 1Y candles are visible. |
| Actual | Title JPM. Price **$362.84**. Analyst **$372.00**. Chart **53 / 53 candles**. Residual income Aligned on the leftover card. |
| Status | **Pass** |
| Evidence | `first-t0.png` / `first-t0.xml` |

**UC-3b — reopen JPM after Back (extension: warm session)**

| Field | Content |
| --- | --- |
| Preconditions | UC-3a and UC-4a. Session still holds the last JPM Detail. |
| Steps | 1. Open Leftover again. 2. Tap **JPM**. 3. Capture Detail at once. |
| Expected | Detail paints JPM from session memory. No empty loading frame. |
| Actual | Same JPM Detail: **$362.84**, **$372.00**, **53 candles**. |
| Status | **Pass** |
| Evidence | `second-t0.png` / `second-t0.xml` |

### UC-4 Close Detail

**UC-4a from JPM · UC-4b from EXPD**

| Field | Content |
| --- | --- |
| Preconditions | Detail is open. |
| Steps | 1. Tap **Back**. |
| Expected | Route clears. Leftover or home list is visible. Detail body is hidden. |
| Actual | After JPM Back, leftover board is shown. After EXPD Back, search/home is shown. |
| Status | **Pass** |
| Evidence | `leftover-again.png`, `expd-nav-*.png` |

### UC-5 One-shot search (EXPD is not in the QA pin)

**UC-5a — cold open (main success, first visit)**

| Field | Content |
| --- | --- |
| Preconditions | UC-1. EXPD is not a QA member. No session paint for EXPD. |
| Steps | 1. Type **EXPD** in Ticker or company. 2. Open. 3. Capture the first frame. |
| Expected | Route is EXPD. First frame may wait on fetch. Chart may be empty until load ends. |
| Actual | Header **EXPD**. Body **Loading detail...**. **Showing 0 / 0 candles**. **No chart data**. MACD asks for 26 candles. |
| Status | **Pass** (cold path) |
| Evidence | `expd-first-instant.png`, `expd-first-1.png` / `.xml` |

**UC-5b — warm reopen (extension: session cache)**

| Field | Content |
| --- | --- |
| Preconditions | UC-5a completed and UC-4b closed Detail. |
| Steps | 1. Type **EXPD**. 2. Open. 3. Capture the first frame. |
| Expected | First frame already shows the last EXPD Detail. Chart candles are present. |
| Actual | Header **Expeditors International**. Price **$186.12**. Analyst **$180.00** (−3.28%). **Showing 53 / 53 candles**. Volume max 14.5M. |
| Status | **Pass** |
| Evidence | `expd-second-instant.png`, `expd-second-1.png` / `.xml` |

## 4. Traceability

| Requirement | Use case | Scenario | Status |
| --- | --- | --- | --- |
| Live QA uses profile `qa` only | UC-1 | Boot | Pass |
| Leftover scans current universe | UC-2 | Review board | Pass |
| Open Detail from leftover | UC-3a | First JPM | Pass |
| Second open of a warm ticker paints at once | UC-3b, UC-5b | Reopen JPM · reopen EXPD | Pass |
| Back hides Detail and keeps the list | UC-4 | Close JPM · close EXPD | Pass |
| Unknown / unpinned ticker is one-shot, not a universe change | UC-5 | EXPD search | Pass |
| Classifier / FCFF / residual six-name live path | — | T, AMZN, CI, UNH, JPM/ACGL, MSFT/AAPL | **Not run** |

## 5. Defects

None opened in this session.

## 6. Residual risk

JPM was already in memory from leftover scan and prior device data. The cold-then-warm proof is **UC-5a → UC-5b** on EXPD.
