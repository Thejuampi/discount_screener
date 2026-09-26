# Windows Loading Optimization

Date: 2026-09-26

Scope: Vantage dashboard loading, Yahoo requests, SEC enrichment, and chart redraws.

## Findings

Android reduced local SQLite chart work in [its first loading pass](../product/current-functionality.md).
Vantage has a different local store.
It already writes history snapshots in one transaction and reads latest snapshots in one query.
Vantage does not restore complete quote and chart inputs from that history table.
Treating those derived history rows as live inputs would give false freshness.

The main Vantage load makes individual summary and chart requests for each symbol.
Bulk enrichment also requested a 10-year monthly chart for each completed symbol.
Only Detail consumes that monthly summary.
The Advisor price path made one chart request for each holding missing from memory.
Chart presentation controls repeated a candle request for the same symbol and range.
The SEC worker repeated insider requests without a freshness interval.
Opportunity polling scored every row while it held the shared screener lock.
The bulk chart cursor skipped symbols that became visible after it passed them.

## Changes

| Area | Change | Result |
| --- | --- | --- |
| Yahoo dashboard summary | Request `summaryProfile` and `formatted=false`. Keep legacy `assetProfile` parsing. | Six recorded response pairs give the same parsed Windows values. |
| Yahoo portfolio prices | Batch four or more missing symbols, with at most 250 per request. | Valid quotes avoid chart calls. Missing quotes and failed batches keep the chart fallback. |
| Bulk charts | Request weekly and hourly charts. Request the monthly chart from Detail. | A completed bulk symbol saves one monthly chart request. |
| SEC insider evidence | Reuse successful results for 24 hours. Retry failed calls after 15 minutes. | Repeated scans do not repeat unchanged insider requests. |
| Financial residual income | Recompute after fundamentals or price changes. Retry failed inputs after 15 minutes. | Unchanged successful inputs do not repeat the calculation. |
| Chart controls | Reuse the same candle response for 60 seconds per mounted chart. | EMA, volume, and overlay changes do not repeat the request. |
| Opportunity polling | Copy one coherent input view under the screener lock. Score rows and estimate paths after release. | Feed writes can continue during projection. |
| Bulk chart queue | Queue each symbol when it becomes visible. Retry chart rate limits with bounded delays. | Late symbols can receive weekly and hourly summaries. |
| Profile changes | Reject older poll results after a universe change. Start a new poll for the new generation. | The old universe cannot restore cleared rows. |
| Feed writes | Check the universe generation while holding the target state lock. | Older workers cannot publish into a newer universe. |
| Detail valuation | Check fundamentals, price, and valuation revision before publication. | A late result cannot replace newer inputs, results, or errors. |
| Model changes | Check the universe generation before publishing a model result. | A late model response cannot restore old rows. |

Batch quotes supply portfolio prices only.
They do not mark analyst targets, fundamentals, or decision tags fresh.
The full summary and required chart paths remain independent.

## Evidence And Limits

The [Yahoo API research](yahoo-api-loading-2026-09-25.md) records six real upstream samples.
Its Android summary transfer fell 45.0 percent with the compact request and gzip.
Windows parser tests compare the original and compact forms for those six symbols.
The Android transfer figure does not measure Vantage load time.

The request savings above follow from the call paths and focused request-count tests.
An offline concurrency test confirms that a feed writer can proceed during opportunity projection.
An offline queue test confirms that a late visible symbol reaches chart enrichment.
An offline race test confirms that an old profile response cannot replace new profile rows.
Offline tests cover late feed writes, Detail valuation, and model changes.
They are estimates for completed symbols and successful batch responses.
Provider retries, missing rows, and rate limits can add requests.
No interactive Windows QA or Vantage timing run took place.
