# Automated behavior examples

Case names identify individual automated tests or named parameter cases. Amounts below are fixture cents, not financial conclusions.

```gherkin
Scenario Outline: Show honest stock exposure
  Given the portfolio fixture <Case>
  When the exposure engine projects the book
  Then the result matches <Expected>

  Examples:
    | Case | Expected |
    | exposure_complete | Two lots worth 10000 and 30000 have total 40000 and weights 2500 and 7500 bps |
    | exposure_partial | One missing quote makes value partial and every weight unavailable |
    | exposure_none | All quotes missing makes total and P/L unavailable |
    | exposure_fraction | Quantity 1 at price 1 stays visible despite a zero rounded cent value |
    | exposure_zero_cost | Zero cost preserves P/L dollars and refuses its percentage |
    | exposure_bad_cost | Negative cost excludes only that lot from paired P/L coverage |
    | exposure_invalid_quote | Zero and negative quotes remain unavailable |
    | exposure_overflow | Huge products never wrap or throw; affected public results are unavailable |
    | exposure_round | Weights use raw products and half-up rounding before two-decimal display |
    | exposure_duplicate | Repeated input symbols retain separate rows and quantities |
    | exposure_paired | Values 10000 and 30000 with costs 5000 and invalid produce P/L 5000 and 10000 bps |
    | exposure_signed_round | Positive and negative P/L round half up on both sides of half-cent boundaries |
    | exposure_overflow_partial | An overflowed lot does not suppress the subtotal from another eligible lot |
    | exposure_aggregate_overflow | Individually available values can overflow the total without producing weights |

Scenario Outline: Preserve research evidence
  Given the position research fixture <Case>
  When the production presenter prepares the position
  Then the row shows <Expected>

  Examples:
    | Case | Expected |
    | research_act | Updated usable Act shows Review opportunity |
    | research_watch | Updated usable Watch shows Monitor |
    | research_avoid | Updated usable Avoid shows Review position |
    | research_stored | Non-current evidence shows Check data and a stored score marker |
    | research_disputed | Disputed evidence shows Check data and Disputed |
    | research_provisional | A trust note or low confidence shows Check data with its reason |
    | research_absent | No opportunity shows Check data and missing quote or analysis reason |
    | research_legacy | Null stance never infers a model comparison from intrinsic alone |
    | research_model | Identity compares its primary with price and labels Model |
    | research_analyst | Analyst range compares its primary with price and labels Analyst |
    | research_model_note | The neutral Model value note preserves a usable research label |
    | research_lens_provisional | Explicit Provisional lens evidence requires Check data despite High row confidence |
    | research_provider | A known provider error replaces Review opportunity with Check data and the error |
    | research_missing_analysis | A usable tracked quote without research shows exposure and Missing analysis |

Scenario Outline: Use the Positions surface
  Given the mounted Positions fixture <Case>
  When Juan performs <Action>
  Then the screen shows <Expected>

  Examples:
    | Case | Action | Expected |
    | positions_summary | Opens a complete book | Stock total, paired P/L, value and two-decimal weight |
    | positions_partial | Opens an incomplete book | Partial labels, separate coverage, unavailable weights |
    | positions_import | Opens the populated menu | Import book remains reachable |
    | positions_empty | Opens an empty book | Direct Import book action |
    | positions_local | Expands an off-feed row | Shares and cost with no dispatched Detail action |
    | positions_detail | Opens a scored row | Existing Detail action and the matching position facts |
    | positions_sort | Selects each sort | Complete deterministic order, missing values last within ties |
    | positions_narrow | Opens at 320 dp | Values, review label, and reason fit without horizontal overflow |
    | positions_large_text | Opens with larger text | Rows expand and expose the same facts |
    | positions_duplicate_detail | Opens a ticker with two input lots | Detail shows both lots rather than an arbitrary first match |
    | positions_stored_total | Opens a book with stored research | Totals disclose unconfirmed quote age and rows mark the stored score |
    | positions_priced_large | Reads a priced row with large text | Ticker, value, weight, valuation, score, and totals remain readable |
    | positions_stored_large | Reads a stored score with large text | The score marker and every value remain readable |
    | positions_sort_detail_return | Selects a sort and returns from Detail | The selected sort remains |
    | positions_sort_tab_return | Selects a sort and returns from another tab | The selected sort remains |

Scenario Outline: Recover the complete dashboard header
  Given the mounted dashboard fixture <Case>
  When Juan performs <Action>
  Then the header has <Expected>

  Examples:
    | Case | Action | Expected |
    | header_down | Scrolls long content down | Hidden title, search, tabs and reclaimed height |
    | header_up | Scrolls up while far from top | Full header without list reset |
    | header_small | Reverses direction below 8 dp | No visibility change |
    | header_horizontal | Scrolls tabs horizontally | No vertical header change |
    | header_focus | Focuses empty search then scrolls | Full pinned header |
    | header_active | Uses an active search state | Full pinned header |
    | header_tab | Changes the selected tab | Full header |
    | header_detail_return | Returns from Detail | Full header |
    | header_short | Scrolls short or empty content | Full header |
    | header_programmatic | Changes data or programmatic list position | No new header motion |
    | header_accessibility | Uses touch exploration | Full pinned header |
    | header_other_tab | Scrolls another vertical main tab down then up | Same hide and recovery behavior |
    | header_shrink | Shrinks content after collapse and attempts to scroll toward earlier items | Full header without a reset loop |
    | header_bottom | Continues toward later items at the list bottom | The header stays hidden |
    | header_focus_back | Leaves empty focused search with Back then scrolls | Focus releases and the header can hide |
    | header_short_search | Expands search in a short viewport | Header bounds fit and internal scroll reaches search and tabs |
```

Tests must assert actual mounted controls for the header interaction rows.
Pure scroll-state tests supplement those tests; they do not replace the mounted path.
The Detail position test must assert the existing earnings card remains first.
