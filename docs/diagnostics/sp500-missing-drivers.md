# SP500 missing drivers

Scanned 2026-08-31T16:07Z. Profile tracked 501 names. Latest rows 496.
Source is the Android `discount_screener_state.sqlite3` copy. List path uses Yahoo. SEC runs on Detail open.

Fix one class at a time. Do not invent numbers. An expected refuse stays expected.

## Progress

| Class | Engine | Next |
| --- | --- | --- |
| `yahoo_missing_marginal_tax` | Domicile 21% proxy when country is set | Rebuild the app. Refresh quotes so `country` is on the snapshot. |
| `sec_non_positive_normalized_fcff` | Latest positive FCFF year (policy/37) | Rebuild. Reopen SNDK. |
| `latest_reported_fcf_non_positive` | Driver path stays open when OCF/CapEx/revenue align | Rebuild. |
| `yahoo_missing_cost_of_debt` | Cash covering debt skips a failed coupon | Levered names with empty Yahoo interest still refuse. |

## Counts

| Class | Count | Status |
| --- | ---: | --- |
| `yahoo_missing_marginal_tax` | 336 | engine_fixed_pending_rebuild — domicile tax proxy |
| `identity_ok` | 65 | closed |
| `latest_reported_fcf_non_positive` | 39 | engine_fixed_pending_rebuild |
| `not_eligible_silent` | 29 | expected refuse, UI reason missing |
| `yahoo_missing_cost_of_debt` | 14 | mixed — net-cash pending rebuild; levered still open |
| `mixed_issuer_missing_lender_book` | 7 | open |
| `financials_missing_book_or_roe` | 5 | open |
| `no_payload` | 5 | open — list hole |
| `sec_non_positive_normalized_fcff` | 1 | open / SNDK pending rebuild |

## Queue

### `yahoo_missing_marginal_tax` (336)

Yahoo annualMarginalTaxRate is empty. Engine now attaches the US domicile 21% row when country is set. Rebuild and refresh quotes.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| NRG | engine_fixed_pending_rebuild | Utilities | Utilities - Independent Power Producers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| APP | engine_fixed_pending_rebuild | Communication Services | Advertising Agencies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MU | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VST | engine_fixed_pending_rebuild | Utilities | Utilities - Independent Power Producers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GNRC | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MCHP | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ON | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CIEN | engine_fixed_pending_rebuild | Technology | Communication Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| Q | engine_fixed_pending_rebuild | Technology | Semiconductor Equipment & Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AKAM | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| JBL | engine_fixed_pending_rebuild | Technology | Electronic Components | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| UAL | engine_fixed_pending_rebuild | Industrials | Airlines | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| APTV | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Parts | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WDC | engine_fixed_pending_rebuild | Technology | Computer Hardware | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FIX | engine_fixed_pending_rebuild | Industrials | Engineering & Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CRH | engine_fixed_pending_rebuild | Basic Materials | Building Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CCL | engine_fixed_pending_rebuild | Consumer Cyclical | Travel Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DECK | engine_fixed_pending_rebuild | Consumer Cyclical | Footwear & Accessories | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EME | engine_fixed_pending_rebuild | Industrials | Engineering & Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WYNN | engine_fixed_pending_rebuild | Consumer Cyclical | Resorts & Casinos | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NXPI | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AMAT | engine_fixed_pending_rebuild | Technology | Semiconductor Equipment & Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| XYL | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GEV | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| STX | engine_fixed_pending_rebuild | Technology | Computer Hardware | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FSLR | engine_fixed_pending_rebuild | Technology | Solar | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TMUS | engine_fixed_pending_rebuild | Communication Services | Telecom Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CMI | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BSX | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KLAC | engine_fixed_pending_rebuild | Technology | Semiconductor Equipment & Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TPR | engine_fixed_pending_rebuild | Consumer Cyclical | Luxury Goods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AZO | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Parts | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CHRW | engine_fixed_pending_rebuild | Industrials | Integrated Freight & Logistics | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CARR | engine_fixed_pending_rebuild | Industrials | Building Products & Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AMD | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VRT | engine_fixed_pending_rebuild | Industrials | Electrical Equipment & Parts | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DAL | engine_fixed_pending_rebuild | Industrials | Airlines | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| STZ | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Brewers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| META | engine_fixed_pending_rebuild | Communication Services | Internet Content & Information | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ADI | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LII | engine_fixed_pending_rebuild | Industrials | Building Products & Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KEYS | engine_fixed_pending_rebuild | Technology | Scientific & Technical Instruments | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| UBER | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LHX | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GLW | engine_fixed_pending_rebuild | Technology | Electronic Components | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TDG | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EXE | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PWR | engine_fixed_pending_rebuild | Industrials | Engineering & Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NKE | engine_fixed_pending_rebuild | Consumer Cyclical | Footwear & Accessories | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HWM | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FICO | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| RL | engine_fixed_pending_rebuild | Consumer Cyclical | Apparel Manufacturing | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TJX | engine_fixed_pending_rebuild | Consumer Cyclical | Apparel Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IDXX | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CEG | engine_fixed_pending_rebuild | Utilities | Utilities - Independent Power Producers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TER | engine_fixed_pending_rebuild | Technology | Semiconductor Equipment & Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DVN | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ALB | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TXN | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ROL | engine_fixed_pending_rebuild | Consumer Cyclical | Personal Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CSCO | engine_fixed_pending_rebuild | Technology | Communication Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HII | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TKO | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TRMB | engine_fixed_pending_rebuild | Technology | Scientific & Technical Instruments | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LITE | engine_fixed_pending_rebuild | Technology | Communication Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MLM | engine_fixed_pending_rebuild | Basic Materials | Building Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DOV | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| RCL | engine_fixed_pending_rebuild | Consumer Cyclical | Travel Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ZTS | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - Specialty & Generic | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DD | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TSN | engine_fixed_pending_rebuild | Consumer Defensive | Farm Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EQT | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VRSK | engine_fixed_pending_rebuild | Industrials | Consulting Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WMT | engine_fixed_pending_rebuild | Consumer Defensive | Discount Stores | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| OTIS | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PNR | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GOOGL | engine_fixed_pending_rebuild | Communication Services | Internet Content & Information | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| URI | engine_fixed_pending_rebuild | Industrials | Rental & Leasing Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GOOG | engine_fixed_pending_rebuild | Communication Services | Internet Content & Information | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SNPS | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AMZN | engine_fixed_pending_rebuild | Consumer Cyclical | Internet Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LRCX | engine_fixed_pending_rebuild | Technology | Semiconductor Equipment & Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TXT | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HUBB | engine_fixed_pending_rebuild | Industrials | Electrical Equipment & Parts | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IR | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LOW | engine_fixed_pending_rebuild | Consumer Cyclical | Home Improvement Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KR | engine_fixed_pending_rebuild | Consumer Defensive | Grocery Stores | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TTWO | engine_fixed_pending_rebuild | Communication Services | Electronic Gaming & Multimedia | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CPRT | engine_fixed_pending_rebuild | Industrials | Specialty Business Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ORLY | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Parts | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| APH | engine_fixed_pending_rebuild | Technology | Electronic Components | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TEL | engine_fixed_pending_rebuild | Technology | Electronic Components | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TDY | engine_fixed_pending_rebuild | Technology | Scientific & Technical Instruments | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FIS | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HON | engine_fixed_pending_rebuild | Industrials | Conglomerates | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TPL | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MSCI | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HRL | engine_fixed_pending_rebuild | Consumer Defensive | Packaged Foods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ADSK | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DVA | engine_fixed_pending_rebuild | Healthcare | Medical Care Facilities | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DDOG | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NEE | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CHTR | engine_fixed_pending_rebuild | Communication Services | Telecom Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WST | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MGM | engine_fixed_pending_rebuild | Consumer Cyclical | Resorts & Casinos | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BLDR | engine_fixed_pending_rebuild | Industrials | Building Products & Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HAL | engine_fixed_pending_rebuild | Energy | Oil & Gas Equipment & Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MCD | engine_fixed_pending_rebuild | Consumer Cyclical | Restaurants | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VMC | engine_fixed_pending_rebuild | Basic Materials | Building Materials | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NOC | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PODD | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CDNS | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WM | engine_fixed_pending_rebuild | Industrials | Waste Management | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DIS | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GE | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ROST | engine_fixed_pending_rebuild | Consumer Cyclical | Apparel Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ETN | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| XYZ | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| QCOM | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AME | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SW | engine_fixed_pending_rebuild | Consumer Cyclical | Packaging & Containers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TT | engine_fixed_pending_rebuild | Industrials | Building Products & Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GM | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Manufacturers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SPGI | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| JBHT | engine_fixed_pending_rebuild | Industrials | Integrated Freight & Logistics | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ODFL | engine_fixed_pending_rebuild | Industrials | Trucking | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| JCI | engine_fixed_pending_rebuild | Industrials | Building Products & Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PEG | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NWSA | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| POOL | engine_fixed_pending_rebuild | Industrials | Industrial Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PH | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HAS | engine_fixed_pending_rebuild | Consumer Cyclical | Leisure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BR | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BKNG | engine_fixed_pending_rebuild | Consumer Cyclical | Travel Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ZBRA | engine_fixed_pending_rebuild | Technology | Communication Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AOS | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SYK | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| OMC | engine_fixed_pending_rebuild | Communication Services | Advertising Agencies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WMB | engine_fixed_pending_rebuild | Energy | Oil & Gas Midstream | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BKR | engine_fixed_pending_rebuild | Energy | Oil & Gas Equipment & Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| STLD | engine_fixed_pending_rebuild | Basic Materials | Steel | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CAH | engine_fixed_pending_rebuild | Healthcare | Medical Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AXON | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GEHC | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VLTO | engine_fixed_pending_rebuild | Industrials | Pollution & Treatment Controls | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HSY | engine_fixed_pending_rebuild | Consumer Defensive | Confectioners | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LNT | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| COR | engine_fixed_pending_rebuild | Healthcare | Medical Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NFLX | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| COO | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HD | engine_fixed_pending_rebuild | Consumer Cyclical | Home Improvement Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SMCI | engine_fixed_pending_rebuild | Technology | Computer Hardware | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ICE | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| COST | engine_fixed_pending_rebuild | Consumer Defensive | Discount Stores | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BALL | engine_fixed_pending_rebuild | Consumer Cyclical | Packaging & Containers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AVY | engine_fixed_pending_rebuild | Consumer Cyclical | Packaging & Containers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KMI | engine_fixed_pending_rebuild | Energy | Oil & Gas Midstream | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SHW | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| A | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ECL | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| F | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Manufacturers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VTRS | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - Specialty & Generic | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| OXY | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| UHS | engine_fixed_pending_rebuild | Healthcare | Medical Care Facilities | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TYL | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| JKHY | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| YUM | engine_fixed_pending_rebuild | Consumer Cyclical | Restaurants | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WAB | engine_fixed_pending_rebuild | Industrials | Railroads | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| STE | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KDP | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Non - Alcoholic | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MA | engine_fixed_pending_rebuild | Financial Services | Credit Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DG | engine_fixed_pending_rebuild | Consumer Defensive | Discount Stores | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LMT | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GPN | engine_fixed_pending_rebuild | Industrials | Specialty Business Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CVNA | engine_fixed_pending_rebuild | Consumer Cyclical | Auto & Truck Dealerships | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LLY | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EW | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TSLA | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Manufacturers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DELL | engine_fixed_pending_rebuild | Technology | Computer Hardware | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LIN | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PG | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EOG | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| COP | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CPAY | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LDOS | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LYV | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CMCSA | engine_fixed_pending_rebuild | Communication Services | Telecom Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| RTX | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ALLE | engine_fixed_pending_rebuild | Industrials | Security & Protection Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ROK | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MSFT | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MDLZ | engine_fixed_pending_rebuild | Consumer Defensive | Confectioners | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GD | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EMR | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| RSG | engine_fixed_pending_rebuild | Industrials | Waste Management | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NDAQ | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DHI | engine_fixed_pending_rebuild | Consumer Cyclical | Residential Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IEX | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PPG | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MCK | engine_fixed_pending_rebuild | Healthcare | Medical Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| T | engine_fixed_pending_rebuild | Communication Services | Telecom Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PHM | engine_fixed_pending_rebuild | Consumer Cyclical | Residential Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PTC | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SYY | engine_fixed_pending_rebuild | Consumer Defensive | Food Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| UPS | engine_fixed_pending_rebuild | Industrials | Integrated Freight & Logistics | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FOXA | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MAS | engine_fixed_pending_rebuild | Industrials | Building Products & Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| COIN | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EBAY | engine_fixed_pending_rebuild | Consumer Cyclical | Internet Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BAX | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PEP | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Non - Alcoholic | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CTVA | engine_fixed_pending_rebuild | Basic Materials | Agricultural Inputs | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TAP | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Brewers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HSIC | engine_fixed_pending_rebuild | Healthcare | Medical Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HLT | engine_fixed_pending_rebuild | Consumer Cyclical | Lodging | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MKC | engine_fixed_pending_rebuild | Consumer Defensive | Packaged Foods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| V | engine_fixed_pending_rebuild | Financial Services | Credit Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CL | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VRSN | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EFX | engine_fixed_pending_rebuild | Industrials | Consulting Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IFF | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DPZ | engine_fixed_pending_rebuild | Consumer Cyclical | Restaurants | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MAR | engine_fixed_pending_rebuild | Consumer Cyclical | Lodging | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MSI | engine_fixed_pending_rebuild | Technology | Communication Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PKG | engine_fixed_pending_rebuild | Consumer Cyclical | Packaging & Containers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MDT | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| XOM | engine_fixed_pending_rebuild | Energy | Oil & Gas Integrated | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ABBV | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KMB | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CVX | engine_fixed_pending_rebuild | Energy | Oil & Gas Integrated | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BIIB | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GILD | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HCA | engine_fixed_pending_rebuild | Healthcare | Medical Care Facilities | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SLB | engine_fixed_pending_rebuild | Energy | Oil & Gas Equipment & Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ITW | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FTV | engine_fixed_pending_rebuild | Technology | Scientific & Technical Instruments | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NDSN | engine_fixed_pending_rebuild | Industrials | Specialty Industrial Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FDX | engine_fixed_pending_rebuild | Industrials | Integrated Freight & Logistics | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LYB | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| UNP | engine_fixed_pending_rebuild | Industrials | Railroads | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TRGP | engine_fixed_pending_rebuild | Energy | Oil & Gas Midstream | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GDDY | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AMCR | engine_fixed_pending_rebuild | Consumer Cyclical | Packaging & Containers | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DRI | engine_fixed_pending_rebuild | Consumer Cyclical | Restaurants | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| J | engine_fixed_pending_rebuild | Industrials | Engineering & Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ABT | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SJM | engine_fixed_pending_rebuild | Consumer Defensive | Packaged Foods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ZBH | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DASH | engine_fixed_pending_rebuild | Consumer Cyclical | Internet Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MMM | engine_fixed_pending_rebuild | Industrials | Conglomerates | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PM | engine_fixed_pending_rebuild | Consumer Defensive | Tobacco | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NVR | engine_fixed_pending_rebuild | Consumer Cyclical | Residential Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WAT | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CTAS | engine_fixed_pending_rebuild | Industrials | Specialty Business Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| REGN | engine_fixed_pending_rebuild | Healthcare | Biotechnology | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KO | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Non - Alcoholic | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CDW | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DE | engine_fixed_pending_rebuild | Industrials | Farm & Heavy Construction Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DHR | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DLTR | engine_fixed_pending_rebuild | Consumer Defensive | Discount Stores | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IQV | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MTD | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BBY | engine_fixed_pending_rebuild | Consumer Cyclical | Specialty Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NSC | engine_fixed_pending_rebuild | Industrials | Railroads | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ROP | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CRWD | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SBUX | engine_fixed_pending_rebuild | Consumer Cyclical | Restaurants | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CHD | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IBM | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VRTX | engine_fixed_pending_rebuild | Healthcare | Biotechnology | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SWKS | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NEM | engine_fixed_pending_rebuild | Basic Materials | Gold | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WBD | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DXCM | engine_fixed_pending_rebuild | Healthcare | Medical Devices | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CSX | engine_fixed_pending_rebuild | Industrials | Railroads | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GEN | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BF.B | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Wineries & Distilleries | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EXPE | engine_fixed_pending_rebuild | Consumer Cyclical | Travel Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EL | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VZ | engine_fixed_pending_rebuild | Communication Services | Telecom Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PLTR | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| APA | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ED | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BDX | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CRM | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PFE | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TSCO | engine_fixed_pending_rebuild | Consumer Cyclical | Specialty Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| INCY | engine_fixed_pending_rebuild | Healthcare | Biotechnology | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| RMD | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GPC | engine_fixed_pending_rebuild | Consumer Cyclical | Auto Parts | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| GWW | engine_fixed_pending_rebuild | Industrials | Industrial Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MO | engine_fixed_pending_rebuild | Consumer Defensive | Tobacco | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| OKE | engine_fixed_pending_rebuild | Energy | Oil & Gas Midstream | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| LH | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KVUE | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| JNJ | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| SWK | engine_fixed_pending_rebuild | Industrials | Tools & Accessories | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CBOE | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AAPL | engine_fixed_pending_rebuild | Technology | Consumer Electronics | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TMO | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| DGX | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| WDAY | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NTAP | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MRK | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CF | engine_fixed_pending_rebuild | Basic Materials | Agricultural Inputs | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ADP | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| BMY | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CLX | engine_fixed_pending_rebuild | Consumer Defensive | Household & Personal Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CME | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TGT | engine_fixed_pending_rebuild | Consumer Defensive | Discount Stores | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| NOW | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EA | engine_fixed_pending_rebuild | Communication Services | Electronic Gaming & Multimedia | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PANW | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| TECH | engine_fixed_pending_rebuild | Healthcare | Biotechnology | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| KHC | engine_fixed_pending_rebuild | Consumer Defensive | Packaged Foods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FAST | engine_fixed_pending_rebuild | Industrials | Industrial Distribution | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ACN | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FTNT | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ADM | engine_fixed_pending_rebuild | Consumer Defensive | Farm Products | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CRL | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| HPQ | engine_fixed_pending_rebuild | Technology | Computer Hardware | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FCX | engine_fixed_pending_rebuild | Basic Materials | Copper | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CPB | engine_fixed_pending_rebuild | Consumer Defensive | Packaged Foods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| RVTY | engine_fixed_pending_rebuild | Healthcare | Diagnostics & Research | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| IT | engine_fixed_pending_rebuild | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ABNB | engine_fixed_pending_rebuild | Consumer Cyclical | Travel Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| EXPD | engine_fixed_pending_rebuild | Industrials | Integrated Freight & Logistics | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| ADBE | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PSX | engine_fixed_pending_rebuild | Energy | Oil & Gas Refining & Marketing | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| VLO | engine_fixed_pending_rebuild | Energy | Oil & Gas Refining & Marketing | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| AMGN | engine_fixed_pending_rebuild | Healthcare | Drug Manufacturers - General | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| CAG | engine_fixed_pending_rebuild | Consumer Defensive | Packaged Foods | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PSKY | engine_fixed_pending_rebuild | Communication Services | Entertainment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| MPC | engine_fixed_pending_rebuild | Energy | Oil & Gas Refining & Marketing | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| PAYX | engine_fixed_pending_rebuild | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |
| FDS | engine_fixed_pending_rebuild | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources) |

### `latest_reported_fcf_non_positive` (39)

Yahoo latest reported FCF is non-positive. Source selection now lets aligned driver FCFF run. Rebuild.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| ORCL | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:LatestFcfNonPositive |
| COHR | engine_fixed_pending_rebuild | Technology | Scientific & Technical Instruments | YahooFinance | YahooFinance:LatestFcfNonPositive |
| PCG | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| BA | engine_fixed_pending_rebuild | Industrials | Aerospace & Defense | YahooFinance | YahooFinance:LatestFcfNonPositive |
| LUV | engine_fixed_pending_rebuild | Industrials | Airlines | YahooFinance | YahooFinance:LatestFcfNonPositive |
| INTC | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:LatestFcfNonPositive |
| NCLH | engine_fixed_pending_rebuild | Consumer Cyclical | Travel Services | YahooFinance | YahooFinance:LatestFcfNonPositive |
| SRE | engine_fixed_pending_rebuild | Utilities | Utilities - Diversified | YahooFinance | YahooFinance:LatestFcfNonPositive |
| NI | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Gas | YahooFinance | YahooFinance:LatestFcfNonPositive |
| BG | engine_fixed_pending_rebuild | Consumer Defensive | Farm Products | YahooFinance | YahooFinance:LatestFcfNonPositive |
| IP | engine_fixed_pending_rebuild | Consumer Cyclical | Packaging & Containers | YahooFinance | YahooFinance:LatestFcfNonPositive |
| XEL | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| PPL | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| AEP | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| FANG | engine_fixed_pending_rebuild | Energy | Oil & Gas E&P | YahooFinance | YahooFinance:LatestFcfNonPositive |
| CMS | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| ETR | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| CNP | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| FE | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| DTE | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| WEC | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| DUK | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| EVRG | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| DOW | engine_fixed_pending_rebuild | Basic Materials | Chemicals | YahooFinance | YahooFinance:LatestFcfNonPositive |
| SO | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| AEE | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| ATO | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Gas | YahooFinance | YahooFinance:LatestFcfNonPositive |
| NUE | engine_fixed_pending_rebuild | Basic Materials | Steel | YahooFinance | YahooFinance:LatestFcfNonPositive |
| MOS | engine_fixed_pending_rebuild | Basic Materials | Agricultural Inputs | YahooFinance | YahooFinance:LatestFcfNonPositive |
| APD | engine_fixed_pending_rebuild | Basic Materials | Specialty Chemicals | YahooFinance | YahooFinance:LatestFcfNonPositive |
| EXC | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| D | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| EIX | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| PNW | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| ES | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Electric | YahooFinance | YahooFinance:LatestFcfNonPositive |
| SOLV | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:LatestFcfNonPositive |
| AES | engine_fixed_pending_rebuild | Utilities | Utilities - Diversified | YahooFinance | YahooFinance:LatestFcfNonPositive |
| AWK | engine_fixed_pending_rebuild | Utilities | Utilities - Regulated Water | YahooFinance | YahooFinance:LatestFcfNonPositive |
| MRNA | engine_fixed_pending_rebuild | Healthcare | Biotechnology | YahooFinance | YahooFinance:LatestFcfNonPositive |

### `not_eligible_silent` (29)

REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| WY | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| VICI | expected | Real Estate | REIT - Diversified |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| CCI | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| IRM | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| AMT | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| DLR | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| SBAC | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| EQIX | expected | Real Estate | REIT - Specialty |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| REG | expected | Real Estate | REIT - Retail |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| INVH | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| EQR | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| FRT | expected | Real Estate | REIT - Retail |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| KIM | expected | Real Estate | REIT - Retail |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| UDR | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| HST | expected | Real Estate | REIT - Hotel & Motel |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| PLD | expected | Real Estate | REIT - Industrial |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| EXR | expected | Real Estate | REIT - Industrial |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| MAA | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| CPT | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| SPG | expected | Real Estate | REIT - Retail |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| O | expected | Real Estate | REIT - Retail |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| BXP | expected | Real Estate | REIT - Office |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| AVB | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| VTR | expected | Real Estate | REIT - Healthcare Facilities |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| DOC | expected | Real Estate | REIT - Healthcare Facilities |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| WELL | expected | Real Estate | REIT - Healthcare Facilities |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| PSA | expected | Real Estate | REIT - Industrial |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| ESS | expected | Real Estate | REIT - Residential |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |
| ARE | expected | Real Estate | REIT - Office |  | REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row. |

### `yahoo_missing_cost_of_debt` (14)

Reported cash covers reported debt. Coupon failure is now not-applicable. Rebuild.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| MPWR | engine_fixed_pending_rebuild | Technology | Semiconductors | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| ALGN | engine_fixed_pending_rebuild | Healthcare | Medical Instruments & Supplies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| CBRE | open | Real Estate | Real Estate Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| ULTA | open | Consumer Cyclical | Specialty Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| CSGP | engine_fixed_pending_rebuild | Real Estate | Real Estate Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| CMG | open | Consumer Cyclical | Restaurants | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| PCAR | open | Industrials | Farm & Heavy Construction Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| FFIV | engine_fixed_pending_rebuild | Technology | Software - Infrastructure | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| MNST | engine_fixed_pending_rebuild | Consumer Defensive | Beverages - Non - Alcoholic | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| LULU | open | Consumer Cyclical | Apparel Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| WSM | open | Consumer Cyclical | Specialty Retail | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| GRMN | engine_fixed_pending_rebuild | Technology | Scientific & Technical Instruments | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| LEN | open | Consumer Cyclical | Residential Construction | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |
| TTD | engine_fixed_pending_rebuild | Communication Services | Advertising Agencies | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods) |

### `mixed_issuer_missing_lender_book` (7)

Classifier marked a factory-plus-lender split. Lender book is missing.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| HPE | open | Technology | Communication Equipment | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |
| CAT | open | Industrials | Farm & Heavy Construction Machinery | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |
| INTU | open | Technology | Software - Application | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |
| MCO | open | Financial Services | Financial Data & Stock Exchanges | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |
| EPAM | open | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |
| SNA | open | Industrials | Tools & Accessories | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |
| CTSH | open | Technology | Information Technology Services | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: lender book missing on a mixed issuer) |

### `financials_missing_book_or_roe` (5)

Residual income refused. Book or ROE is missing.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| CNC | open | Healthcare | Healthcare Plans | SecEdgar | Residual income refused. Book or ROE is missing. |
| ARES | open | Financial Services | Asset Management | SecEdgar | Residual income refused. Book or ROE is missing. |
| WRB | open | Financial Services | Insurance - Property & Casualty | SecEdgar | Residual income refused. Book or ROE is missing. |
| IVZ | open | Financial Services | Asset Management | SecEdgar | Residual income refused. Book or ROE is missing. |
| BX | open | Financial Services | Asset Management | SecEdgar | Residual income refused. Book or ROE is missing. |

### `no_payload` (5)

No symbol_latest row. The list never stored this ticker.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| BK | open |  |  |  | No symbol_latest row. The list never stored this ticker. |
| SATS | open |  |  |  | No symbol_latest row. The list never stored this ticker. |
| FISV | open |  |  |  | No symbol_latest row. The list never stored this ticker. |
| FOX | open |  |  |  | No symbol_latest row. The list never stored this ticker. |
| NWS | open |  |  |  | No symbol_latest row. The list never stored this ticker. |

### `sec_non_positive_normalized_fcff` (1)

Latest aligned FCFF is positive; policy/37 keeps that year. Rebuild the app.

| Symbol | Status | Sector | Industry | Source | Detail |
| --- | --- | --- | --- | --- | --- |
| SNDK | engine_fixed_pending_rebuild | Technology | Computer Hardware | YahooFinance | YahooFinance:MissingDriverEvidence (fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources); SecEdgar:MissingDriverEvidence (non_positive_normalized_fcff: aligned annual FCFF evidence has a non-positive robust margin) |

## How to refresh

Copy the device DB, then run the scanner:

```
python scripts/scan-android-missing-drivers.py --db path/to/discount_screener_state.sqlite3
```

