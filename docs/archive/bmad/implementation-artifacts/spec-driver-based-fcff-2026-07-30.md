# Driver-based FCFF policy — 2026-07-30

Status: implementation contract for Windows and Android. Desktop is deferred.

## Purpose

The valuation engine must not combine a normalized FCF level with the raw
endpoint FCF CAGR. That produced contradictory estimates for CapEx-cycle
businesses such as AMZN. The model remains independent: analyst targets and
market price are validation signals only.

## Operating model

For aligned annual rows, the operating FCFF bridge is:

```text
FCFF = operating cash flow + interest expense × (1 − effective tax rate) − CapEx
```

Revenue normalizes the drivers:

```text
OCF margin       = OCF / revenue
CapEx intensity  = CapEx / revenue
FCFF margin      = FCFF / revenue
```

The latest revenue is projected for five years. Revenue growth and FCFF
margin fade linearly to the stable-growth path. Bear/base/bull use the
historical 25th percentile, median, and 75th percentile of the driver rows.
Quantiles use the same nearest-rank rounding implementation on both surfaces.

An annual CapEx intensity is an investment spike when it is greater than 1.5×
the median of at least two prior intensities. Spikes are excluded from the
base margin/intensity baseline when at least two non-spike rows remain; the
full set is used otherwise. The spike years remain in diagnostics and reason
codes.

Reported FCF is retained as `latest_fcf_dollars` / `latestFcfDollars`.
`fcf_run_rate_dollars` / `fcfRunRateDollars` is the normalized FCFF actually
used by the driver model. Diagnostics expose the latest revenue, normalized
FCFF, OCF margin, CapEx intensity, spike years, and growth driver.

## Routing and fallback

Financial services continue to use residual income with cost of equity.
`Unclassified` and `NotEligible` refuse with an explicit reason. If fewer than
three aligned positive OCF/revenue/CapEx rows or fewer than two growth rows are
available, the operating path uses the explicit legacy FCF-history fallback
with `valuation_driver=fcf_history_fade`; it does not call analyst data.

Yahoo and SEC annual driver sources are part of the input fingerprint. Missing
tax or interest is represented as provisional/defaulted provenance; missing
required operating drivers prevents the driver path.

## Acceptance pins

- AMZN retains reported 2025 FCF of $7.695B and uses driver-normalized FCFF of
  approximately $41.295B for the sampled fixture; base growth is revenue-led,
  not −51%/−9%; 2025 CapEx intensity is diagnosed as a spike.
- Bear ≤ base ≤ bull is asserted structurally.
- No intrinsic/price, analyst, or market cap is used as a valuation output cap.
- Windows and Android produce cent-for-cent results on the shared AMZN driver
  fixture and the existing cohort fixtures.
