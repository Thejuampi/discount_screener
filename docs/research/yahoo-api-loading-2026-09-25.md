# Yahoo API Loading Research

Date: 2026-09-25
Scope: Android provider loading and anonymous Yahoo website requests.

## Result

Smaller Yahoo requests preserve every Android field across six paired samples.
Summary transfers decrease by 45.0 percent with Yahoo gzip compression.
Batch quote transfers decrease by 72.3 percent with Yahoo gzip compression.
These measurements describe response bodies. They do not measure phone loading time.

The tested batch alternatives cannot replace full summary, statement, or candle requests.
The complete cold-load request count therefore remains unchanged by this patch.

## Baseline

The previous 501-symbol offline probe issued 1,505 Yahoo requests:

| Request | Count |
| --- | ---: |
| Summary | 501 |
| Year chart | 501 |
| Annual statement series | 501 |
| Session setup | 2 |

The app already batches prices for saved rows, with 250 symbols per request.
A new row also needs analyst targets and company classification.
The existing batch quote response cannot replace those inputs.

## Published Sources And Website Inspection

The [Yahoo developer catalog](https://developer.yahoo.com/api/) lists Fantasy Sports and Sign In With Yahoo.
I found no Finance endpoint contract there.
The [Yahoo development help page](https://help.yahoo.com/kb/SLN14513.html) directs development questions to that catalog.

The public [AAPL page](https://finance.yahoo.com/quote/AAPL/) embeds request URLs and response data.
Its requests include batch quotes with `fields`, summaries with `modules`, and multi-symbol spark data.
The summary request uses `summaryProfile` for company information.

The served [spark JavaScript](https://s.yimg.com/uc/finance/webcore/js/sparkLine.be4a748c75a1f617d549.mjs) groups requests in batches of 20.
It collects requests for 500 milliseconds and requests only `close` values.
These details describe this captured website version. They are not published API guarantees.

[Yahoo data documentation](https://help.yahoo.com/kb/SLN2310.html) identifies data providers and exchange delays.
[Yahoo adjusted-close documentation](https://nz.help.yahoo.com/kb/finance-for-web/adjusted-close-sln28256.html) describes split and dividend adjustments.
Price-series substitution must therefore preserve both data fields and adjustment semantics.

## Endpoint Experiments

Samples: AAPL, MSFT, JPM, BRK-B, TSM, and SPY.
The set includes operating companies, financial companies, a foreign issuer, and an ETF.

| Endpoint | Observed result | Decision |
| --- | --- | --- |
| `v7/finance/quote?symbols=...&fields=...` | Six symbols retain every consumed quote field. | Request consumed fields. |
| Batch quote with analyst and classification fields | Sector and industry labels return; requested targets and analyst counts remain absent. | Keep summary inputs for new rows. |
| `v10/finance/quoteSummary/{symbol}` | Compact modules and `formatted=false` preserve consumed values. | Use compact dashboard requests. |
| `v7/finance/spark?symbols=...` | Six symbols return closing prices only. | Keep full candle requests. |
| Spark with `indicators=open,high,low,close,volume` | Responses still contain only `close`. | Reject as a candle substitute. |
| `v10/finance/quoteSummary/AAPL,MSFT,...` | HTTP 404. | Keep individual summary requests. |
| `v8/finance/chart/AAPL,MSFT` | HTTP 404. | Keep individual chart requests. |
| `fundamentals-timeseries/.../AAPL,MSFT,...` | HTTP 200, but no reported values. | Reject as a statement batch. |
| `fundamentals-timeseries/.../AAPL` | Reported annual values exist. | Retain the current statement path. |

The multi-symbol statement response treats the complete comma-separated string as one symbol.
An HTTP 200 response alone therefore does not prove usable batch support.
These experiments rule out the tested request forms. They do not prove that every possible batch form fails.
A later comparison-page asset request returned HTTP 429. Live exploration stopped at that response.

## Measured Transfer Sizes

The baseline uses the existing request parameters.
The candidate requests only consumed quote fields and compact summary modules.
Both requests use the same anonymous session and request gzip compression.

| Response bodies | Baseline bytes | Candidate bytes | Reduction |
| --- | ---: | ---: | ---: |
| Six summaries, gzip | 29,198 | 16,047 | 45.0% |
| Six summaries, decoded | 104,566 | 38,492 | 63.2% |
| One six-symbol quote batch, gzip | 3,576 | 990 | 72.3% |
| One six-symbol quote batch, decoded | 15,422 | 5,281 | 65.8% |

The [capture manifest](evidence/yahoo-payloads-2026-09-25/metrics.json) records URLs, status codes, byte counts, compression, duration, and hashes.
Durations represent one request per form. They cannot establish an end-to-end speed improvement.

The decoded reduction exceeds 50 percent because the candidate omits formatting, unused earnings trends, and officer details.
Quote selection removes unused market fields. Yahoo still includes some default fields.
Paired fixture tests compare every parsed value and missing-data reason for all six symbols.

## Android Changes

| Change | Consumer or constraint |
| --- | --- |
| Select eight quote fields. | Saved-row prices, names, profitability, and earnings dates. |
| Replace `assetProfile` with `summaryProfile`. | Sector, industry, country, and company classification. |
| Remove `earningsTrend` from dashboard requests. | The dashboard parser does not consume it. |
| Request `earningsTrend` alone for consensus. | The earnings consensus consumer still receives its estimates. |
| Set dashboard `formatted=false`. | Numeric parsing accepts plain and wrapped values. |
| Retain legacy `assetProfile` parsing. | Existing response fixtures and fallback shapes remain readable. |

The change adds no synchronization blocks or request concurrency.
The existing governor still bounds provider work.
Cache selection, refresh behavior, financial calculations, and provider selection keep their current rules.

## Reproduction And Validation

Run the bounded research script explicitly:

```powershell
python scripts/probe-yahoo-payloads.py --live --out "$env:TEMP/yahoo-payload-research"
```

The script uses anonymous cookies in memory and omits crumbs from saved URLs.
It stops on HTTP 429. Automated tests never call this script.
It writes public provider samples and a capture manifest to the chosen directory.

[Paired fixtures](../../apps/android/app/src/test/resources/yahoo/payload-2026-09-25/) contain the first capture set.
The transfer table uses a second capture set with gzip enabled.
Live market fields can change between capture sets.

The new tests first failed on compact numeric shapes and oversized requests.
The implementation then passed all paired comparisons and exact-request tests.
Existing provider tests also passed.

The first full run exposed two timing failures and a warm-launch test race.
The timing probes passed when rerun together.
The full-suite rerun still exceeded the cold-load timing budget.
Validation now runs both timing classes separately, with one test worker.
The existing CI workflow still invokes the complete Gradle suite directly.
The time budgets and request-count assertions remain unchanged.
The warm-launch probe previously treated 400 milliseconds without requests as completion.
It now waits for repository loading to finish before checking saved-data reuse.
Its request-count assertion still requires zero per-symbol calls on the second launch.

The Android validation command covers core tests, app tests, and the debug build.
All validation stages passed after the test-harness corrections.
Device timing remains unmeasured in this research pass.

## Remaining Opportunities

Request counts can decrease further if fewer symbols need full statements or charts during a load.
That requires a separate review of cache freshness and each screen's data requirements.
The current evidence does not justify replacing full candles with spark values or inventing missing inputs.
