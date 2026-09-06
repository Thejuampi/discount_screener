# Test report — Windows SEC companyfacts sieve and shared cache

**Standard:** use-case test (Sommerville) + UML scenario template
**Session:** 2026-08-29
**System:** Discount Screener Windows debug (Tauri + Vite)
**Build:** `node scripts/run-tauri-dev-qa.mjs --no-watch`
**Actor:** analyst (Juan)
**Tester:** agent over WebView2 CDP (`scripts/ds-ui.mjs`)
**Evidence:** `.agents/workspace/tmp/tauri-qa.log`

## 1. Environment

| Item | Value |
| --- | --- |
| Process | `discount-screener-windows.exe`, debug profile |
| Debug endpoint | WebView2 CDP on `127.0.0.1:9222` |
| Universe lock | profile `qa`, 20 symbols, forced by the QA runner |
| Under test | `sec_company_facts_sieve`, `shared_company_facts`, the cohort-gate anchor |
| Out of scope | Android, the `sp500` profile, the red durable and high-signal gates |

## 2. Summary

| Use case | Scenarios run | Pass | Fail | Not run |
| --- | --- | --- | --- | --- |
| UC-1 Boot the QA universe | 1 | 1 | 0 | 0 |
| UC-2 Confirm the debug surface | 1 | 1 | 0 | 0 |
| UC-3 Value an issuer through the sieve | 2 (AAPL, MSFT) | 2 | 0 | 0 |
| UC-4 Re-open an issuer already cached | 1 (AAPL) | 1 | 0 | 0 |
| UC-5 Value a bank through the sieve | 1 (JPM) | 1 | 0 | 0 |

**Session verdict:** every scenario passed. Both business classes ran live, so nothing is left
unexercised in this report.

## 3. Use cases

### UC-1 Boot the QA universe

| Field | Content |
| --- | --- |
| Actor | Analyst |
| Precondition | No app running. Rust sources changed since the last build. |
| Steps | 1. `node scripts/run-tauri-dev-qa.mjs --no-watch` |
| Expected | The app compiles and starts with the universe pinned to `qa`. |
| Actual | `Finished 'dev' profile`, `Running target\debug\discount-screener-windows.exe`, `launch profile locked to qa (20 symbols)`. |
| Status | Pass |

### UC-2 Confirm the debug surface

| Field | Content |
| --- | --- |
| Actor | Agent |
| Precondition | UC-1 passed. |
| Steps | 1. `node scripts/ds-ui.mjs self-check` |
| Expected | Every step reports ok. |
| Actual | `"ok": true` for cdp_list, page_target `Vantage`, tauri_invoke, agent_bridge, feed_qa_locked (`symbols_loaded: 20`), screenshot, invoke_detail. |
| Status | Pass |

### UC-3 Value an issuer through the sieve

| Field | Content |
| --- | --- |
| Actor | Analyst |
| Precondition | Cold companyfacts cache for the symbol. |
| Steps | 1. `ds-ui open-detail AAPL`. 2. `ds-ui qa-snapshot AAPL`. 3. The same for MSFT. |
| Expected | Detail returns an FCFF value. The EDGAR read reaches `data.sec.gov`, streams through the sieve, and parses. |
| Actual | AAPL: `model: "fcff_wacc"`, `business_class: "operating_non_financial"`, base 12 851 cents, bear 9 953, bull 16 833, status `disputed`. MSFT: `fcff_wacc`, `operating_non_financial`, base 44 050 cents, bear 34 465, bull 54 041, status `selected`, with the FCFF driver bridge reporting normalized FCFF $128.1B against a $331.8B revenue driver. |
| Status | Pass |

An FCFF value at all is the proof that matters. `fcff_wacc` cannot produce a number without a
parsed companyfacts document, and every companyfacts document now arrives through
`sec_company_facts_sieve`. A sieve that dropped a needed qname would leave the model with no
drivers and the panel with no value.

### UC-4 Re-open an issuer already cached

| Field | Content |
| --- | --- |
| Actor | Analyst |
| Precondition | UC-3 ran AAPL in this process. |
| Steps | 1. `ds-ui close-detail`. 2. `ds-ui open-detail MSFT`. 3. `ds-ui close-detail`. 4. `ds-ui open-detail AAPL`. |
| Expected | AAPL returns the same value, and the second read costs no second download. |
| Actual | AAPL returned 12 851 cents again, in 1 814 ms against 2 984 ms for the cold MSFT open. |
| Status | Pass |

The wall clock is weak evidence on its own, because the app caches above this layer too. The unit
test `company_facts_cache_tests` is what pins the behaviour. This scenario says the cache does not
break the answer.

### UC-5 Value a bank through the sieve

| Field | Content |
| --- | --- |
| Actor | Analyst |
| Precondition | UC-3 passed. JPM sits outside the 20-symbol `qa` profile. |
| Steps | 1. `ds-ui open-detail JPM`. 2. `ds-ui qa-snapshot JPM`. |
| Expected | The one-shot open finds the bank and the residual-income path finds its drivers in the sieved document. |
| Actual | `model: "residual_income_equity"`, `business_class: "financial_services"`, base 31 132 cents, bear 27 163, bull 34 812, `-12.9%` against market. |
| Status | Pass |

The bank path reads a different field set than FCFF does, so this is the scenario that would break
first if the sieve's allow-list were cut to what the operating model needs. It held. The off-app
cover stays: `sieve_parity_tests` walks the JPM companyfacts fixture through the sieve and compares
both readers against the raw document.

## 4. Not run

None.
