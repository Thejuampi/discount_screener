# Yahoo Compact Payload Fixtures

Captured on 2026-09-25 through anonymous Yahoo requests.
These responses contain no session cookies or crumbs.

Each summary pair contains identical consumed values before Android normalization.
The baseline requests the previous dashboard modules with default formatting.
The candidate requests `summaryProfile`, omits `earningsTrend`, and sets `formatted=false`.

The batch quote pair compares default fields against the eight fields Android consumes.
Both responses include AAPL, MSFT, JPM, BRK-B, TSM, and SPY.

`YahooCompactPayloadParityTest` compares all parsed values and missing-data reasons for each symbol.
`YahooCompactPayloadRequestTest` verifies exact request URLs and rejects unexpected fallback calls.

See [the research report](../../../../../../../../docs/research/yahoo-api-loading-2026-09-25.md) for sources and measurements.
