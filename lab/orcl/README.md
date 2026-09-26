# ORCL investment wave, 2026-09-24

This case tests an ORCL value without changing the app or another company's estimate.

**Result:** about $80 per share under one stated scenario. This is a conditional calculation, not a validated intrinsic value.

The app's $99.44 DCF remains unvalidated. This case does not replace it.

## Filed evidence

| Item | Amount | Source |
| --- | ---: | --- |
| FY2026 revenue | $67.357 billion | [Oracle FY2026 results](https://investor.oracle.com/investor-news/news-details/2026/Oracle-Announces-Record-Q4-and-FY-2026-Results-Driven-by-Cloud-Infrastructure--Cloud-Applications/) |
| FY2026 operating cash | $31.977 billion | [Oracle FY2026 results](https://investor.oracle.com/investor-news/news-details/2026/Oracle-Announces-Record-Q4-and-FY-2026-Results-Driven-by-Cloud-Infrastructure--Cloud-Applications/) |
| FY2026 CapEx | $55.663 billion | [Oracle FY2026 results](https://investor.oracle.com/investor-news/news-details/2026/Oracle-Announces-Record-Q4-and-FY-2026-Results-Driven-by-Cloud-Infrastructure--Cloud-Applications/) |
| FY2026 free cash flow | −$23.686 billion | [Oracle FY2026 results](https://investor.oracle.com/investor-news/news-details/2026/Oracle-Announces-Record-Q4-and-FY-2026-Results-Driven-by-Cloud-Infrastructure--Cloud-Applications/) |
| FY2027 Q1 free cash flow | −$5.396 billion | [Oracle FY2027 Q1 filing](https://www.sec.gov/Archives/edgar/data/1341439/000119312526389274/orcl-20260831.htm) |
| FY2027 Q1 customer prepayments | $11.363 billion | [Oracle FY2027 Q1 filing](https://www.sec.gov/Archives/edgar/data/1341439/000119312526389274/orcl-20260831.htm) |
| August 2026 cash, debt, preferred, shares | $36.369 billion, $125.337 billion, $4.954 billion, 3.024 billion | [Oracle FY2027 Q1 filing](https://www.sec.gov/Archives/edgar/data/1341439/000119312526389274/orcl-20260831.htm) |

Oracle expects FY2027 CapEx above FY2026 CapEx. The first quarter alone used $28.499 billion.

Oracle guides to at least $90 billion in FY2027 revenue. Its older [OCI plan](https://www.oracle.com/a/ocom/docs/corporate/financial-analyst-meeting-2025-magouyrk.pdf) projects rapid growth through FY2030.

FY2024 had $18.673 billion operating cash, $6.866 billion CapEx, and $3.514 billion interest expense.

The illustrative FY2024 unlevered cash margin is 27.5% with a 21% assumed marginal tax rate.

Source: [Oracle FY2024 results](https://investor.oracle.com/investor-news/news-details/2024/Oracle-Announces-Fiscal-2024-Fourth-Quarter-and-Fiscal-Full-Year-Financial-Results/).

## Scenario assumptions

| Input | Assumption | Status |
| --- | --- | --- |
| FY2027 revenue | $90 billion | Oracle guidance; execution remains uncertain |
| FY2028–2030 revenue | $131, $172, $202 billion | Our OCI plan bridge plus flat $58 billion other revenue |
| Later revenue growth | 8% through FY2033, then 4% | Our assumption |
| FY2027 CapEx | $75 billion | Our illustration; Oracle gave no full-year amount |
| FY2027 operating cash margin | FY2026 margin, excluding $4.592 billion customer financing prepayments | Our cash-quality adjustment |
| Interest and tax | FY2026 interest; 21% marginal tax | Our assumption |
| Stable FCFF margin | 26% by FY2033 | Our assumption; FY2024 was 27.5% under the same tax assumption |
| WACC and terminal growth | 11.41% and 2.5% | Provisional app rate; our growth assumption |
| Balance sheet | August 31, 2026 | Filed amounts |
| Customer financing reserve | $11.363 billion | Our treatment of the filed Q1 prepayment |
| Share count | 3.024 billion | Filed August common shares; later dilution excluded |

The calculation excludes filed FY2027 Q1 cash flows from future cash flows. The August balance sheet already includes those flows.

It subtracts net debt, preferred capital, and the Q1 customer advance from enterprise value.

This reserve prevents the model from treating advance cash as free equity cash. It does not model preferred conversion or future financing.

It treats prepayments as financing in the cash-flow path. It does not value all related future service obligations separately.

## Sensitivity, USD per share

Each row changes one input from the $79.54 scenario.

| Change | Conditional value |
| --- | ---: |
| FY2027 CapEx $55.663 billion | $107.28 |
| FY2027 CapEx $75 billion | $79.54 |
| FY2027 CapEx $100 billion | $43.66 |
| FY2027 CapEx $113.996 billion, equal to four Q1 amounts | $23.58 |
| Stable margin 20% | $42.73 |
| Stable margin 32% | $116.34 |
| Stable margin reached in FY2036 | $44.95 |
| WACC 10% | $116.17 |
| WACC 13% | $50.90 |
| Shares rise 5% | $75.75 |

The terminal value supplies 106% of the enterprise value in the $79.54 scenario.

The explicit cash-flow years have a negative present value. The result depends on an unverified recovery.

These values are scenarios. They are not a probability interval or investment advice.

The cents show arithmetic precision. They do not show forecast accuracy.

## Reproduce

Run `python lab/orcl/valuation_case.py` from the repository root.

Run `python -m unittest discover -s lab/orcl -p 'test_*.py'` to check the filed arithmetic and scenario behavior.
