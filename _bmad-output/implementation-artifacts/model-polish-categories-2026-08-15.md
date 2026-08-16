# Model polish — one category at a time

Goal: **good dollars**, not more screens. Loop: `hardcoded()` → one rule → `cached()` → `live()` only to refresh a pack.

Do **not** pull a number toward market price. Do **not** start the next category until this one has a written result.

## Order

| id | Category | Status | One lever |
| --- | --- | --- | --- |
| **A** | Residual income **level** on financials | **done — franchise persist cap** | End ROE = min(ROE0, CoE+500). Policy `/20`. Raw ROE0 forever blew up ACGL/CI |
| **B** | Residual income **inputs** (one-year ROE, payout, parent book) | **done — three named facts** | Median of last ≤4 NI/beginning-book ROEs; `DividendsCommonStockCash`; NCI book minus `MinorityInterest` |
| **C** | Operating FCFF **level** (AAPL, AMZN, T) | **done — grower owner earnings** | A name that grows ≥3% and spends above maintenance uses owner earnings. Flat high-CapEx networks stay on reported FCFF. Policy `/24` |
| **D** | Operating FCFF **fan** (NVDA, TSLA, MRK) | **done — growth cap + g_stable** | Near-term growth stays within 1200 bps of g_stable. Terminal g does not exceed demonstrated recent growth (`/23`) |
| **E** | Operating **WACC / CoD** (T, CHTR) | **done — coverage synthetic** | Market yield, then coverage spread, then coupon. Cheap old debt does not set rd. Policy `/25` |
| **F** | Operating **regime** (NVDA) | **done — high-median secular** | A 10%+ median grower with 3 of 4 years positive is secular. One soft year is not a cycle. Policy `/26` |
| **G** | Secular **near growth** (NVDA $74) | **done — half demonstrated** | Secular near-term growth is min(raw, max(stable+1200, raw/2), 2500). 90% forever stays refused. Policy `/31` |
| **H** | **Path CAP + industry margin** | **done — policy `/33`** | Hold, fade, quality discount, and industry margin from our drivers. Street is scoreboard only |

Windows math stays out until Category A has a locked Android rule. Then port. Do not drift.

## Category A — why the dollars look bad

The residual model adds extra profit above the cost of equity, then drives extra profit to **zero** in **5 years**. Value then sits near **book**.

Live sniff (not a target):

| name | book ≈ | model | market |
| --- | --- | --- | --- |
| JPM | $130 | $148 | $363 |
| CI | $155 | $178 | $283 |
| UNH | $110 | $119 | $402 |

Book and shares sniff clean on JPM. This is the **rule**, not a unit bug.

Two later knobs (do **not** turn both now):

1. How many years extra profit lasts  
2. Whether the last year still has extra profit (end ROE vs cost of equity)

Knob 1 cannot take JPM to $360. Knob 2 can keep value above book. Measure knob 1 first.

## Category A steps

1. `hardcoded()` JPM + CI. Old value sat near book. ROE = cost of equity ⇒ value = book. **Done.**  
2. More fade years raise value but stay below 2× book. **Done.**  
3. Production knob: end ROE = cost of equity + **300 bps**. Leftover extra profit is capitalized at **stable growth** (~3%), not at ROE×payout (that explodes). Policy `/17`. Android + Windows. **Done.**  
4. Result: JPM and CI premiums are **above 20% of book** and JPM stays **below 2.5× book**. Same pack through `cached()`. Identity still holds. Not pulled to market price.  

Next is **B** (one-year ROE / payout / parent book). Category C later set the 6-year AMZN window to 4 years (**$121.18**).

## Category C — operating FCFF level

One knob. Policy `/19`.

The 6-year AMZN pack used a **5-year** driver window. Year 2025 flags as a CapEx spike, so the OCF median sat on 2021–24 and kept the 2021 trough (**1232** bps). That is the Windows **$88.40** collapse.

**Rule:** `DRIVER_RECENT_WINDOW = 4`, same as near-term growth. CapEx-spike years still drop from the CapEx median. The last four years are 2022–25; after the 2025 spike drop, OCF is 2022–24 (**1478** bps). Named SEC years only. Not a Street match.

| pack | before | after |
| --- | --- | --- |
| AMZN 4-year hardcoded | **$123.74**, OCF **1478** | **$123.74**, OCF **1478** (window already 4) |
| AMZN 6-year (adds 2020–21) | **$87.55**, OCF **1232** | **$120.04**, OCF **1478** |
| Windows 6-year baseline | **$88.40**, run **$49.25B** | **$121.18**, run **$67.10B**, growth 1141 |
| Windows cohort AAPL | — | **$111.01** (clear of the CapEx collapse) |
| Windows isolation T | — | **$59.72** on that fixture; contract T is already 4 years |

Do not pull AMZN to Street ~$325.

## Residual remeasure — B drivers + A math

Cached slim SEC + Yahoo quote. Polish rates. **No new residual lever.** Premium is leftover excess ROE, not a Street match.

| name | ROE0 | book / sh | model | vs book |
| --- | --- | --- | --- | --- |
| JPM | **1625** | **$130.30** | **$228.53** | **1.75×** |
| CI | **1156** | **$155.32** | **$306.86** | **1.98×** |
| UNH | **2104** | **$109.87** | **$205.51** | **1.87×** |
| ACGL | **2211** | **$64.39** | **$164.42** | **2.55×** |

Stale wave-1b UNH ($118 / ROE 1227) was one-year trough. Median ROE **2104** clears the 1.2× hug. Slim UNH book is still NCI-inclusive **$109.87**; parent **$94.11B** would raise the premium, not cut it. ACGL slim still misses `DividendsCommonStockCash` so retention stays **10000**; it does not hug book.

Hardcoded JPM **$223.38** / CI **$285.12** stay thinkable. AMZN 4y **$123.74**, 6y **$120.04** / Windows **$121.18**. AAPL cohort **$111.01**. Thinkable bar is met.

## Category B — residual inputs

Three named facts. No invented cash. No price target.

| lever | old | new | sniff (cached SEC, not a target) |
| --- | --- | --- | --- |
| ROE0 | last year only | `medianOf` last ≤4 `NI / beginning book` | UNH 1227 → **2214**. JPM 1615 → **1624** |
| ACGL payout | SEC miss → Yahoo retain 10000 | `DividendsCommonStockCash` $1.9B | retention **5641** |
| UNH book | NCI-inclusive $100.09B | NCI − `MinorityInterest` | parent **$94.11B** |

One-year issuers stay on that year (JPM fixture 1500). Parent `StockholdersEquity` still wins when the year matches.

## Category D — operating FCFF fan

NVDA slim keeps 2012 PPE/revenue next to 2022. The engine treated that as **one year** at ~573% growth.

Shipped (D1): growth is computed only when `year - prior.year == 1`. Android + Windows.

Shipped (D2): scenario growth is a **400 bps band around the median**. Historical IQR stays in `growthDispersionBps`. A 0% / 126% year pair is not stacked for the whole projection. Policy `/18`. Android + Windows.

Synthetic 0/0/126/126 fan: width **73709 → thinkable** (≤ 12000). Dispersion stays **12600**. Base growth is still the median. AMZN 4-year floor stays ≥ **$100**. Windows 6-year AMZN is now **$121.18** under `/19` (Category C). Do not pull to Street.

No further D lever. Leftovers are Lens labels, not model dollars.

## Out of this category

Lens labels, price-matching asserts.
