# Spec review round 1

Sensei approved. Advisor returned revise for duplicate-symbol Detail selection.

| Finding | Resolution |
| --- | --- |
| Detail could select the wrong lot by first match | Detail receives and shows every exact-symbol lot |
| Total overflow could retain misleading weights | Overflow suppresses all weights |
| Paired P/L needed numeric proof | Add exposure_paired and signed rounding Cases |
| Stored total needed visible disclosure | Add positions_stored_total |
| A collapsed list can become short | An unconsumed vertical gesture restores the header; add header_shrink |

The revised spec preserves existing Closeness order, which places missing dates last.
The parent schedules Luna waves sequentially to match the build workflow.
