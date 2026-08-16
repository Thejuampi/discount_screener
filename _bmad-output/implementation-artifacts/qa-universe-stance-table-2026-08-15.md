# QA universe stance table — 2026-08-15

Wave 1 measure plus Wave 1b official `core` fill. Wave 2 ships `valuation-judgment/2` thinkability. No engine formula change.

Membership: Android `apps/android/app/src/main/assets/profiles/qa.txt` (20 symbols). Feed was not grown.

Official functions Wave 2 will use:

- `ValuationDecisionPolicy.scenarioWidthBps(bear, base, bull)`
- `ValuationDecisionPolicy.differenceBps(identityBase, streetBase)`
- Street complete = `ValuationJudgmentPolicy` street book rule (`0 < low ≤ base ≤ high`, coverage Sufficient, freshness not Stale/Unknown)
- Predicted Wave-2 status uses plan.v3 refuse/width: width `> 12000` ⇒ identity incomplete (`UnusableIdentityFan` + `IncompleteIdentity`). Soft ≤ 12000 stays complete.

**Cents are integers.** Display dollars in the 2026-08-15 Detail captures are not goldens.

**Do not use** `.agents/workspace/tmp/live-comparison-table.json` or the 2026-07-30 Android store identity dollars. That store is `business-class-policy/3` (`growth=recent_window_fade_to_stable`). The 2026-08-15 captures are driver-based FCFF (`policy/16`).

---

## Evidence keys

| Key | What it is |
| --- | --- |
| `qa-batch1` | 2026-08-15 Android Detail NVDA |
| `qa-batch2` | 2026-08-15 Android Detail ACGL |
| `qa-batch3` | 2026-08-15 Android Detail CI + META |
| `qa-batch4` | 2026-08-15 Android Detail JPM |
| `core` | `ValuationDecisionPolicy` / `DcfAnalysisEngine.resolveCostOfEquity` on those literals |
| `classifier` | `DcfAnalysisEngine.classifyBusiness` on sector/industry text (not dollars) |
| `store-2026-07-30` | `android-db-inspection/state.sqlite3` class/model/envelope only. Identity dollars rejected |
| `wave-1b` | 2026-08-15 `DcfAnalysisEngine.compute` + `ValuationJudgmentAssembler.assemble`. SEC companyfacts first (financial residual via `SecResidualFacts`; operating FCFF via policy QNames). Yahoo quoteSummary for sector, beta, street, and named fallbacks. Market params: FRED miss, Yahoo `^TNX` rf **470** bps + implied-index ERP **442** |
| `Unknown` | No same-era capture or `core` output. Blocks any cell Wave 2 must branch on |

---

## 20-name stance

Official identity dollars and widths are **wave-1b** `compute()` cents unless a note says capture-only. Street is Yahoo target low / **median** / high from the same quoteSummary pull.

| symbol | class | model | identity bear/base/bull ¢ | Street low/base/high ¢ | differenceBps | relation | status | WACC/CoE | identity quality | reported vs normalized cash | reason codes | evidence | widthBps | Street complete | predicted Wave-2 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| T | OperatingNonFinancial | FcffWacc | 7174 / 14127 / 22630 | 2000 / 2800 / 3600 | 13383 | Disputed | Disputed | WACC 583 | not labeled soft | SEC FY OCF−dev CapEx series | DisputedGap | wave-1b | **10941** | yes | **Disputed** |
| AMZN | OperatingNonFinancial | FcffWacc | 6085 / 12495 / 15474 | 23000 / 32500 / 40000 | 8892 | Disputed | Disputed | WACC 1029 | not labeled soft | SEC FY OCF−dev CapEx series | DisputedGap | wave-1b | **7514** | yes | **Disputed** |
| AAPL | OperatingNonFinancial | FcffWacc | 9057 / 12254 / 15440 | 21500 / 33500 / 40000 | 9287 | Disputed | Disputed | WACC 963 | not labeled soft | SEC FY OCF−dev CapEx series | DisputedGap | wave-1b | **5209** | yes | **Disputed** |
| CI | FinancialServices | ResidualIncomeEquity | 16622 / 17768 / 18651 | 29000 / 34300 / 40000 | **6350** | Disputed | Disputed | CoE 696 | solid (thin fan) | n/a (residual) | DisputedGap | wave-1b. Capture qa-batch3 was 18000/19200/20200 width **1146** gap **5645** | **1142** | yes | **Disputed** |
| JPM | FinancialServices | ResidualIncomeEquity | 13844 / 14777 / 15492 | 30500 / 37200 / 42000 | 8628 | Disputed | Disputed | CoE 891 | solid (thin fan) | n/a | DisputedGap | wave-1b SEC residual. qa-batch4 had no fan (IllegalModelPair) | **1115** | yes | **Disputed** |
| ACGL | FinancialServices | ResidualIncomeEquity | 7741 / 8297 / 8661 | 9500 / 11100 / 13400 | **2890** | Tension | Tension | CoE 687 | not labeled soft | n/a | TensionNoPrimary | wave-1b. SEC dividends **missing**; Yahoo retention 10000. Capture gap **2466** Aligned | **1109** | yes | **Tension** |
| MSFT | OperatingNonFinancial | FcffWacc | 41177 / 55573 / 74566 | 40000 / 55000 / 87000 | 104 | Aligned | Identity | WACC 960 | not labeled soft | SEC FY OCF−dev CapEx series | IdentityPrimary | wave-1b | **6008** | yes | **Identity / Aligned** |
| NVDA | OperatingNonFinancial | FcffWacc | 38150 / 215016 / 350888 | 18000 / 30000 / 50000 | 15102 | Disputed | Disputed | WACC 1316 | **soft** (wide) | SEC FY series (not qa-batch1 owner-earnings print) | SoftIdentity, DisputedGap | wave-1b. Capture qa-batch1 fan 1330/52400/382000 width **72647** | **14545** | yes | **Street** + UnusableIdentityFan |
| UNH | FinancialServices | ResidualIncomeEquity | 11110 / 11848 / 12412 | 31300 / 49000 / 52900 | 12211 | Disputed | Disputed | CoE 788 | solid (thin fan) | n/a | DisputedGap | wave-1b. Parent book stale after 2014; used NCI-inclusive 2025 book | **1099** | yes | **Disputed** |
| JNJ | OperatingNonFinancial | FcffWacc | 20743 / 31118 / 50803 | 19000 / 28000 / 30500 | 1055 | Aligned | Identity | WACC 650 | not labeled soft | SEC FY OCF−dev CapEx series | IdentityPrimary | wave-1b | **9660** | yes | **Identity / Aligned** |
| XOM | OperatingNonFinancial | FcffWacc | 7697 / 14473 / 31667 | 14200 / 16800 / 18500 | 1488 | Aligned | Street | WACC 700 | **soft** (wide) | SEC FY from historic CIK 0000034088 (ticker map 0002115436 is 10-Q only) | SoftIdentity, StreetPrimary | wave-1b | **16562** | yes | **Street** + UnusableIdentityFan |
| BAC | FinancialServices | ResidualIncomeEquity | 3720 / 3977 / 4175 | 6200 / 6850 / 7500 | 5307 | Disputed | Disputed | CoE 947 | solid (thin fan) | n/a | DisputedGap | wave-1b SEC residual (CIK 0000070858) | **1144** | yes | **Disputed** |
| V | FinancialServices | ResidualIncomeEquity | 4080 / 4367 / 4557 | 33000 / 42000 / 45000 | 16233 | Disputed | Disputed | CoE 826 | solid (thin fan) | n/a | DisputedGap | wave-1b. Same-FY SEC shares **missing**; Yahoo shares 1704112694 + SEC book | **1092** | yes | **Disputed** |
| WMT | OperatingNonFinancial | FcffWacc | 3621 / 4890 / 7316 | 8100 / 14000 / 15500 | 9645 | Disputed | Disputed | WACC 726 | not labeled soft | SEC FY OCF−dev CapEx series | DisputedGap | wave-1b | **7556** | yes | **Disputed** |
| GOOGL | OperatingNonFinancial | FcffWacc | 18591 / 27883 / 35425 | 34000 / 42800 / 51500 | 4221 | Tension | Tension | WACC 968 | not labeled soft | SEC FY OCF−dev CapEx series | TensionNoPrimary | wave-1b | **6037** | yes | **Tension** |
| META | OperatingNonFinancial | FcffWacc | 80862 / 142667 / 176694 | 58000 / 75000 / 100000 | 6217 | Disputed | Disputed | WACC 958 | not labeled soft | SEC FY series (capture had owner-earnings lift) | DisputedGap | wave-1b. Capture qa-batch3 fan 35100/134000/716000 width **50813** | **6717** | yes | **Disputed** |
| TSLA | OperatingNonFinancial | FcffWacc | 4948 / 18076 / 66822 | 12500 / 41500 / 60000 | 7864 | Disputed | Disputed | WACC 1171 | **soft** (wide) | SEC FY OCF−dev CapEx series | SoftIdentity, DisputedGap | wave-1b | **34230** | yes | **Street** + UnusableIdentityFan |
| HD | OperatingNonFinancial | FcffWacc | 23732 / 31303 / 42545 | 31000 / 37750 / 43000 | 1867 | Aligned | Street | WACC 828 | **soft** (provisional inputs) | SEC FY OCF−dev CapEx series | SoftIdentity, StreetPrimary | wave-1b. W1-N01 hole filled | **6010** | yes | **Street** (soft aligned) |
| PG | OperatingNonFinancial | FcffWacc | 15991 / 22579 / 37193 | 14300 / 16200 / 18600 | 3290 | Tension | Tension | WACC 649 | not labeled soft | SEC FY OCF−dev CapEx series | TensionNoPrimary | wave-1b | **9390** | yes | **Tension** |
| MRK | OperatingNonFinancial | FcffWacc | 14737 / 29710 / 84100 | 10500 / 14150 / 15500 | 7095 | Disputed | Disputed | WACC 613 | **soft** (wide) | SEC FY OCF−dev CapEx series | SoftIdentity, DisputedGap | wave-1b | **23347** | yes | **Street** + UnusableIdentityFan |

Class and sector text for wave-1b come from the Yahoo quoteSummary pull. Identity dollars are `compute()` cents, not the 2026-07-30 store.

### Wave 1b residual drivers (SEC first)

| symbol | book $ | begin book $ | NI $ | div $ | shares | ROE bps | retention bps | fallback |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| JPM | 362.438B (parent 2025-12-31) | 344.758B | 55.681B common | 16.625B PaymentsOfDividends | 2.7815B diluted | 1615 | 7014 | none |
| BAC | 303.243B (parent 2025-12-31) | 293.963B | 29.055B common | 9.563B ordinary | 7.6809B diluted | 988 | 6709 | none |
| CI | 41.713B (parent 2025-12-31) | 41.033B | 5.957B NetIncomeLoss | 1.611B common | 268.563M diluted | 1452 | 7296 | none |
| ACGL | 24.206B (parent 2025-12-31) | 20.820B | 4.359B common | **missing** | 375.9M diluted | 2094 | **10000 Yahoo** | Yahoo payout 0 |
| UNH | 100.090B (NCI-inclusive 2025) | 98.268B | 12.056B | 7.916B common | 911M diluted | 1227 | 3434 | parent book stale |
| V | 37.909B (NCI-inclusive 2025-09-30) | 39.137B | 20.058B | 4.634B | **Yahoo 1.704B** | 5125 | 7690 | SEC same-FY shares missing |

Never FCFF on these six.

---

## Consequence columns (Wave 2 branch)

| symbol | widthBps | Street complete | current status | predicted Wave-2 | flip? |
| --- | --- | --- | --- | --- | --- |
| NVDA | 14545 | yes | Disputed | Street + UnusableIdentityFan | **yes** |
| TSLA | 34230 | yes | Disputed | Street + UnusableIdentityFan | **yes** |
| MRK | 23347 | yes | Disputed | Street + UnusableIdentityFan | **yes** |
| XOM | 16562 | yes | Street | Street + UnusableIdentityFan | no (already Street) |
| META | 6717 | yes | Disputed | Disputed | no |
| CI | 1142 | yes | Disputed | Disputed | no |
| ACGL | 1109 | yes | Tension | Tension | no |
| JPM | 1115 | yes | Disputed | Disputed | no |
| BAC | 1144 | yes | Disputed | Disputed | no |
| other 11 | see table | yes | = predicted | = predicted | no |

**Flips listed before thinkability:** NVDA, TSLA, MRK. Count = **3**.

**CI lock:** predicted stays **Disputed**. Width 1142 ≤ 12000. Gap 6350 > 5000. No `if ticker == CI`.

**After Wave 2:** policy `/2` uses the predicted column. Live META width 6717 stays **Disputed**. W2-P02 is a synthetic META-like fan > 12000, not the live 6717 row. ACGL after-column stays **Tension** (2890). Capture 2466 is history.

---

## Official cents used by `core`

Wave-1 capture checks stay. Wave-1b `compute()` cents are the 20-row official set.

| id | inputs | result |
| --- | --- | --- |
| W1-P02 NVDA capture width | `scenarioWidthBps(1330, 52400, 382000)` | 72647 |
| W1-P03 META capture width | `scenarioWidthBps(35100, 134000, 716000)` | 50813 |
| W1-P04 CI capture gap | `differenceBps(19200, 34300)` | 5645 |
| W1-E01 ACGL capture gap | `differenceBps(8663, 11100)` | **2466** |
| W1b CI width | `scenarioWidthBps(16622, 17768, 18651)` | **1142** |
| W1b CI gap | `differenceBps(17768, 34300)` | **6350** |
| W1b JPM width | `scenarioWidthBps(13844, 14777, 15492)` | **1115** |
| W1b BAC width | `scenarioWidthBps(3720, 3977, 4175)` | **1144** |
| W1b NVDA width | `scenarioWidthBps(38150, 215016, 350888)` | **14545** |
| W1b META width | `scenarioWidthBps(80862, 142667, 176694)` | **6717** |

---

## Judge-input field populate

`ValuationJudgmentPolicy.identityComplete` reads: subject match, currency/scale, `Computed`, class, `valuationUnavailableReason`, legal pair, `scenarioWidthBps`.

It does **not** read the five candidate driver fields.

| field | on `judge()` input? | NVDA | META | CI | ACGL | JPM |
| --- | --- | --- | --- | --- | --- | --- |
| `latestFcfDollars` | **no** | yes on UI ($96.7B) | yes on UI ($46.1B) | n/a residual | n/a residual | not published |
| `normalizedFcffDollars` | **no** | yes on UI ($32.3B) | yes on UI ($89.0B) | n/a | n/a | not published |
| `growthDriver` | **no** | UI: growth from revenue drivers | same + CapEx spike 2024,2025 | engine residual uses `roe_retention`; not a judge input | same | not published |
| `bookValuePerShareCents` | **no** | n/a FCFF | n/a FCFF | residual published ⇒ engine had book; cents not printed | residual published; P/B 1.44 | not published; P/B 2.73 + price can derive book |
| `roe0Bps` | **no** | n/a for judge | n/a | UI ROE 16.76% | UI ROE 19.94% | UI ROE 17.79% |

**Wave 2 rule:** width gate always. Do **not** fail-closed on these five fields until they sit on the finished analysis `judge()` already reads. Drop W2-E03.

---

## Persist vs ephemeral

| object | persist? | where |
| --- | --- | --- |
| `ValuationJudgment` | **ephemeral** | `ValuationJudgmentAssembler.assemble` inside `ScreenDataProjectionEngine`. No SQLite column. No cache key |
| `ProjectedValuationJudgment` | ephemeral snapshot | projection DTO only |
| `DcfAnalysis` | **persisted** | `EvaluatedSymbolState.dcfAnalysis` in `symbol_revision` / `symbol_latest` |

Wave 1 JPM probe is ephemeral. It must not write qa cache. Production persist is Wave 3.

Compose live QA must use a **warm** store and `presentValuationJudgment`, not raw DCF soft quality.

After Wave 2, if judgment stays ephemeral, revision need not include `valuation-judgment/2`. If anyone persists judgment later, the key must include `/2`.

---

## Width histogram vs 12000

Wave-1b official widths (20/20):

| widthBps | names |
| --- | --- |
| 1092 | V |
| 1099 | UNH |
| 1109 | ACGL |
| 1115 | JPM |
| 1142 | CI |
| 1144 | BAC |
| 5209 | AAPL |
| 6008 | MSFT |
| 6010 | HD |
| 6037 | GOOGL |
| 6717 | META |
| 7514 | AMZN |
| 7556 | WMT |
| 9390 | PG |
| 9660 | JNJ |
| 10941 | T |
| 14545 | NVDA |
| 16562 | XOM |
| 23347 | MRK |
| 34230 | TSLA |

**Empty band around 12000:** 10942–14544 inclusive (**3603** bps ≥ 2000). Contains 12000. 20-width cliff **exists**.

**≤ 1 flip exception:** does **not** apply (3 flips: NVDA, TSLA, MRK).

**Cut 12000:** **GO** on the cliff rule. CI stays Disputed.

---

## Five sniffs

### 1. NVDA / META drivers — **policy-correct**

Same policy, opposite sign.

| name | reported FCF | normalized FCFF | sign | UI driver marks |
| --- | --- | --- | --- | --- |
| NVDA | $96.7B | $32.3B | ~3× haircut | OCF margin 20.9%, CapEx intensity 2.8%, no spike, growth from revenue |
| META | $46.1B | $89.0B | ~2× lift | OCF margin 52.7%, CapEx intensity 27.3%, spike 2024–2025 |

Code path (`DcfAnalysisEngine` driver build):

- NVDA: no spike and latest CapEx is not ≥ maintenance + 500 bps. `ownerEarningsBase` is false. Base margin = **median non-neg aligned annual FCFF margin** on latest revenue. Haircut vs a high latest reported FCF is the policy.
- META: CapEx spike years set `investmentWave`. Owner-earnings margin (OCF + after-tax interest − **maintenance** CapEx) exceeds annual base. `ownerEarningsBase` is true. Lift vs reported FCF (reported subtracts full growth CapEx) is the policy.

Not a mapping bug. Not SEC class error. Wave 3 documents only. Do not average reported and normalized cash. Do not haircut toward price.

Fans are unusable vs own base (72647 / 50813 > 12000). Wave 2 should mark them incomplete if the 12000 cut ships.

### 2. ACGL stance — **Aligned is policy-correct**

| item | value |
| --- | --- |
| Identity base | 8663 ¢ ($86.63) qa-batch2 |
| Street | 9500 / 11100 / 13400 ¢ ($95 / $111 / $134) |
| Official `differenceBps(8663, 11100)` | **2466 ≤ 2500** |
| Display % on Detail | **−12.23% vs price** ($86.63 vs $98.71). That is not the stance gap |
| One-sided (111−86.63)/86.63 | ~28%. **Not** the policy |
| Street freshness enum | not printed. Live **Aligned** means assemble scored Street complete (freshness not Unknown/Stale) |
| W1-E02 | If freshness were Unknown, `streetComplete` is false and relation must be SingleSource. Live row is **not** that case |

Do not flip ACGL to Tension. Do not use price $98.71 in the relation.

### 3. JPM / BAC drivers — residual did not publish

**JPM 2026-08-15 (qa-batch4)**

| item | value |
| --- | --- |
| Class | Financial Services / Banks - Diversified |
| Street | $305 / $372 / $420 |
| Identity | none |
| UI reasons | **Class and model pair is not legal.** Identity is incomplete. Primary is the analyst range |
| Stored envelope | Computed illegal pair (live), not the `model=None` stub `ValuationJudgmentAssemblerTest` already covers |
| ROE | 17.79% (1779 bps) |
| P/B | 2.73 |
| Price | $362.84 |
| Book | not printed; derivable from P/B + price |
| Shares | **Unknown** on the capture |
| Retention | **Unknown** on the capture |

Wave 3 may call residual only if **all** required drivers exist (shares, book or P/B+price, ROE, retention). Wave 1 does **not** list all of them. If any stay missing: `model=None` + `MissingDrivers`. Never FCFF. IncompleteIdentity is not success when later compose finds all drivers.

**JPM 2026-07-30 store (ephemeral probe, not persist):** `businessClass=FinancialServices`, `model=None`, `resolverState=NotEligible`, `MissingMarketCap`, 0/0/0. No `fundamental-timeseries`. Shares missing. Retention missing. That older envelope is **not** IllegalModelPair.

**BAC:** in `qa.txt`. No 2026-08-15 Detail. Store envelope same shape as JPM (`None` / MissingMarketCap / 0/0/0). Street dollars from that store are not used as 2026-08-15 truth.

### 4. CoE shrink — **shrink correct**

Identity: `CoE = rf + round(shrunkBeta × ERP / 1000)`, `shrunk = round((beta×67 + prior×33) / 100)`.

ACGL fixture (W1-R02): beta **292**, prior **900** (financials/insurance), rf **463**, ERP **442**.

- shrunk = 493
- premium = 218
- CoE = **681 bps**

Capture display is **6.80%** (680 bps). One-bps display rounding. Do not raise CoE to chase ACGL $98.71 or CI $283.

CI live beta was not printed. Store-era beta 303 + same rf/ERP yields 684 bps vs capture 6.89%. CI shrink is **not** closed on a recovered 2026-08-15 beta. Formula is not the defect.

### 5. Quant Lens — no lone Fair; unusable fan still used

Code: `QuantLensExpectedValuePolicy` + `QuantLensUiModels`.

NVDA / META / CI all have model base vs Street base `differenceBps` > 2500. Lens primary status is **Disputed**. Chip label **Disputed**. Primary line names **both** DCF and analyst. Footer **No single EV**. No lone Fair. No Strong.

On the unusable NVDA/META fan, Lens still treats the three model cents as complete scenario anchors (soft quality) and Disputes them against Street. Wave 2 fail-closed test: `UnusableIdentityFan` / incomplete ⇒ not Strong, not agreement, not EV model-primary.

---

## W1 scenario map

| id | result |
| --- | --- |
| W1-P01 | 20 rows exist. Each has an evidence source |
| W1-P02 | NVDA width 72647 > 12000 (core test) |
| W1-P03 | META width 50813 > 12000 (core test) |
| W1-P04 | CI `differenceBps` 5645 > 5000 (core test) |
| W1-N01 | HD identity/Street dollars are Unknown |
| W1-N02 | Old Windows AMZN json dollars are not in this table |
| W1-E01 | `differenceBps(8663, 11100) = 2466 ≤ 2500` (core test) |
| W1-E02 | Live ACGL Street is complete. Unknown-freshness would be incomplete; that is not the live row |
| W1-R01 | CI predicted Disputed |
| W1-R02 | ACGL shrink CoE = 681 bps (core test) |

---

## Wave 2 branch this table allows

| branch | table says |
| --- | --- |
| Thinkability cut 12000 | **GO** (20-width cliff 10942–14544; 3 flips) |
| CI | stay Disputed |
| NVDA / TSLA / MRK compose | demand UnusableIdentityFan |
| META compose | stay Disputed (wave-1b width 6717 ≤ 12000) |
| ACGL | wave-1b **Tension** 2890. Capture 2466 Aligned used different retention. SEC dividends missing |
| JPM / BAC | residual **published**. Disputed vs complete Street |
| V | residual published with Yahoo shares (SEC same-FY shares missing) |
| Driver-path completeness | not on `judge()` input |
| Lens EV rewrite | no (no lone Fair) |
