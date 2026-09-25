# Parent integration checks

These checks supplement the wave reports. They do not change the approved behavior.

| Check | Required evidence |
| --- | --- |
| Case mapping is unique | Each Examples row has its own automated test or named parameter case |
| Weight rounding | Assert raw ratio rounding at both sides of a basis-point half boundary |
| Paired P/L | Assert both 5000 cents and 10000 bps for the paired fixture |
| Signed cent rounding | Assert below, at, and above the half-cent boundary in both directions |
| Overflow pairing | An unavailable public market value cannot produce a supported P/L row or full P/L coverage |
| Quote freshness | An explicit non-current quote cannot support current research even if the score row says Updated |
| Summary coverage | Missing exposure in a default fixture row cannot make a partial book appear complete |
| Detail facts | Display every exact-symbol input lot and preserve the shared book denominator |
| UI overflow | Test the real row at 320 dp and large text; inspect required facts and reasons |
| Narrow missing data | Include unavailable value and weight, a long reason, and an earnings label without hiding the ticker |
| Compact summary | Use two value/P-L blocks at normal width and allow expansion with large text |
| Local refusal | Explain unavailable weight and P/L; never show negative cost as valid cost |
| Blank trust note | Empty and whitespace notes act as absent in Core and the production presenter |
| Header content | Check actual reclaimed height, input focus, and list state after upward recovery |

Wave A's final audit splits exposure_bad_cost/exposure_paired and research_act/research_model into separate tests.
Wave C owns mounted positions_* evidence; presenter tests alone do not close those Cases.
