Feature: Valuation Posterior

  The Core publishes an interval, in cents, with the width attributed to the
  inputs that caused it. A wide interval is an honest answer to a hard name; a
  refusal is that same answer with the estimate thrown away and the uncertainty
  deleted. So refusal is reserved for two things — an issuer we do not value,
  and a required input that was not measured — and nothing about being uncertain
  can reach either.

  The bridge is two steps, and they are separable on purpose. Subtracting net
  debt belongs to the operating path, which is the only one that produces an
  enterprise value; residual income on book values the equity directly and never
  crosses it. Folding the subtraction into publication would have forced every
  financial issuer to assert a net debt of zero — a fabricated measurement in
  place of a step that simply does not apply.

  Behaviour is added to this feature by adding a row to the table below. A new
  outline requires an entry in manifest.toml saying what no existing table
  covers (FR-44).

  Scenario Outline: Publishing a Valuation Posterior across the equity bridge

    Given a firm value of <firm> with standard deviation <firm_sd>
    And net debt of <net_debt> with standard deviation <debt_sd>
    And <shares> diluted shares with standard deviation <share_sd>
    And a Business Class of <class>
    When the Valuation Posterior is published
    Then the published percentiles are <p05>, <p50> and <p95> cents
    And the publication outcome is <outcome>

    Examples: bridging and quantizing
      | case                      | firm    | firm_sd | net_debt | debt_sd | shares | share_sd | class                   | p05    | p50    | p95    | outcome   |
      | all-inputs-exactly-known  | 1000.00 |    0.00 |     0.00 |    0.00 |   10.0 |     0.00 | operating_non_financial |  10000 |  10000 |  10000 | published |
      | no-debt                   | 1000.00 |  100.00 |     0.00 |    0.00 |   10.0 |     0.00 | operating_non_financial |   8355 |  10000 |  11645 | published |
      | levered                   | 1000.00 |  100.00 |   200.00 |    0.00 |   10.0 |     0.00 | operating_non_financial |   6355 |   8000 |   9645 | published |
      | net-cash                  | 1000.00 |  100.00 |  -200.00 |    0.00 |   10.0 |     0.00 | operating_non_financial |  10355 |  12000 |  13645 | published |
      | wider-firm-value          | 1000.00 |  200.00 |     0.00 |    0.00 |   10.0 |     0.00 | operating_non_financial |   6710 |  10000 |  13290 | published |
      | debt-adds-width           | 1000.00 |  100.00 |   200.00 |  100.00 |   10.0 |     0.00 | operating_non_financial |   5674 |   8000 |  10326 | published |
      | share-count-adds-width    | 1000.00 |  100.00 |     0.00 |    0.00 |   10.0 |     1.00 | operating_non_financial |   7674 |  10000 |  12326 | published |
      | enormous-uncertainty      | 1000.00 |10000.00 |     0.00 |    0.00 |   10.0 |     0.00 | operating_non_financial |-154485 |  10000 | 174485 | published |
      | financial-services        | 1000.00 |  100.00 |     0.00 |    0.00 |   10.0 |     0.00 | financial_services      |   8355 |  10000 |  11645 | published |
      | not-eligible              | 1000.00 |  100.00 |     0.00 |    0.00 |   10.0 |     0.00 | not_eligible            | ABSENT | ABSENT | ABSENT | refused   |
      | unclassified              | 1000.00 |  100.00 |     0.00 |    0.00 |   10.0 |     0.00 | unclassified            | ABSENT | ABSENT | ABSENT | refused   |
      | firm-value-absent         |  ABSENT |  100.00 |     0.00 |    0.00 |   10.0 |     0.00 | operating_non_financial | ABSENT | ABSENT | ABSENT | refused   |
      | net-debt-absent           | 1000.00 |  100.00 |   ABSENT |    0.00 |   10.0 |     0.00 | operating_non_financial | ABSENT | ABSENT | ABSENT | refused   |
      | share-count-absent        | 1000.00 |  100.00 |     0.00 |    0.00 | ABSENT |     0.00 | operating_non_financial | ABSENT | ABSENT | ABSENT | refused   |
      | share-count-zero          | 1000.00 |  100.00 |     0.00 |    0.00 |    0.0 |     0.00 | operating_non_financial | ABSENT | ABSENT | ABSENT | refused   |

  # Rows worth reading as a set:
  #
  #   no-debt                   Hand-checkable end to end. 1000 over 10 shares is
  #                             100.00 a share; the firm's 100.00 of standard
  #                             deviation is 10.00 a share; 1.6449 * 10.00 is 16.45.
  #                             8355 / 10000 / 11645 cents.
  #   levered / net-cash        The bridge. Net debt moves the median by exactly its
  #                             per-share amount and, at zero width, moves neither
  #                             percentile relative to it.
  #   wider-firm-value          Double the input width, double the interval. FR-33
  #   debt-adds-width           requires monotone widening in every input, and each
  #   share-count-adds-width    of these three widens through a different partial.
  #   enormous-uncertainty      A 10x-of-value standard deviation still publishes,
  #                             negative fifth percentile and all. There is no path
  #                             from "very uncertain" to a refusal (FR-32), which is
  #                             the row that replaces the nine Disputed names.
  #   all-inputs-exactly-known  A zero standard deviation states an exactly known
  #                             input -- a device these rows use to isolate one
  #                             source of width at a time. It publishes a point
  #                             interval rather than refusing, because zero width is
  #                             not missing evidence. No production input is ever
  #                             this precise; the row is here as the boundary.
  #   not-eligible /            Eligibility refusals. The class itself is the reason,
  #   unclassified              and unclassified is a statement about our coverage
  #                             rather than about the issuer.
  #   share-count-zero          A share count is a divisor and a structural fact.
  #                             Zero is not a small number, it is a broken record.
