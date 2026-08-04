Feature: Business Class

  Every issuer resolves to exactly one class, and text the taxonomy does not
  recognize resolves to unclassified — never to the operating model. An insurer
  routed through a cash-flow model produces a confident number with no economic
  content, because an insurer's cash flow is float rather than owner earnings.

  Behaviour is added to this feature by adding a row to the table below. A new
  outline requires an entry in manifest.toml saying what no existing table
  covers (FR-44).

  Scenario Outline: Business Class from sector and industry text

    Given an issuer in sector "<sector>" and industry "<industry>"
    And the instrument is <instrument>
    When the Business Class is resolved
    Then the Business Class is <class>

    Examples: routing text to a model
      | case                     | sector             | industry                        | instrument    | class                  |
      | operating-technology     | Technology         | Software - Infrastructure       | common_equity | operating_non_financial |
      | operating-energy         | Energy             | Oil & Gas E&P                   | common_equity | operating_non_financial |
      | operating-from-slugs     | consumer-cyclical  | auto-manufacturers              | common_equity | operating_non_financial |
      | operating-no-sector      | ABSENT             | Semiconductors                  | common_equity | operating_non_financial |
      | bank                     | Financial Services | Banks - Diversified             | common_equity | financial_services      |
      | insurer                  | Financial Services | Insurance - Property & Casualty | common_equity | financial_services      |
      | credit-card-issuer       | Financial Services | Credit Services                 | common_equity | financial_services      |
      | health-plan              | Healthcare         | Healthcare Plans                | common_equity | financial_services      |
      | drug-manufacturer        | Healthcare         | Drug Manufacturers - General    | common_equity | operating_non_financial |
      | pharmaceutical-retailer  | Healthcare         | Pharmaceutical Retailers        | common_equity | operating_non_financial |
      | medical-devices          | Healthcare         | Medical Devices                 | common_equity | operating_non_financial |
      | healthcare-sector-only   | Healthcare         | ABSENT                          | common_equity | unclassified            |
      | real-estate-trust        | Real Estate        | REIT - Diversified              | common_equity | not_eligible            |
      | fund-holding-technology  | Technology         | Software - Infrastructure       | fund          | not_eligible            |
      | keyword-inside-a-word    | Consulting         | Bankruptcy Advisory             | common_equity | unclassified            |
      | unrecognized-text        | Widget Wrangling   | Bespoke Widgetry                | common_equity | unclassified            |
      | no-text-at-all           | ABSENT             | ABSENT                          | common_equity | unclassified            |

  # Rows worth reading as a set:
  #
  #   health-plan /             The split that the shipping engine gets right only
  #   drug-manufacturer /       because its key list was patched by hand, and which
  #   pharmaceutical-retailer / a bare "health" key would get wrong in both
  #   medical-devices           directions. A health plan underwrites risk against
  #                             reserves; a drug maker sells product.
  #   healthcare-sector-only    The CI case. Healthcare with no industry could be
  #                             either, so it refuses. Guessing here is what failing
  #                             closed exists to prevent.
  #   keyword-inside-a-word     "bank" is a key and "bankruptcy" begins with it, so
  #                             substring matching would value a restructuring
  #                             advisor on book. Matching is on whole words.
  #   operating-from-slugs      Provider slugs and display names are the same text
  #                             after normalization, so the taxonomy carries one
  #                             spelling rather than two.
  #   fund-holding-technology   The provider's instrument type outranks the sector
  #                             text: a fund holding operating companies is a fund.
  #   unrecognized-text /       Nothing reaches the operating model by falling
  #   no-text-at-all            through. There is no default class (FR-30).
