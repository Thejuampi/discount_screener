Feature: Intrinsic Value

  Value is the integral of the whole discounted cash-flow path. Growth relaxes
  continuously toward the terminal rate, and growth is charged for the capital it
  consumes, so there is no explicit horizon, no hold period, and no seam between
  projected and terminal cash flows — the terminal value is the limit of the same
  integrand rather than a separate calculation bolted onto the end.

  Behaviour is added to this feature by adding a row to the table below. A new
  outline requires an entry in manifest.toml saying what no existing table
  covers (FR-44).

  Scenario Outline: Intrinsic Value from a continuously fading growth path

    Given a base cash flow of <base>
    And a Growth Posterior of <g0> bps
    And a growth path fading to <g_inf> bps at <fade> per year
    And a return on capital of <roc> bps
    And a discount rate of <wacc> bps
    When the Intrinsic Value is resolved
    Then the Intrinsic Value is <value> within 1 cent
    And the outcome is <outcome>
    And the absence reason is <reason>

    Examples: integrating the path
      | case                        | base   | g0     | g_inf | fade   | roc    | wacc   | value    | outcome  | reason                |
      | flat-path                   | 100.00 |    300 |   300 |   0.20 |   1200 |    800 |  1500.00 | resolved | ABSENT                |
      | high-return-compounder      | 100.00 |   1500 |   300 |   0.20 |   2500 |    800 |  2624.13 | resolved | ABSENT                |
      | average-return-grower       | 100.00 |   1500 |   300 |   0.20 |   1200 |    800 |  1923.59 | resolved | ABSENT                |
      | value-neutral-return        | 100.00 |   1500 |   300 |   0.20 |    800 |    800 |  1250.00 | resolved | ABSENT                |
      | low-return-grower           | 100.00 |   1500 |   300 |   0.20 |    600 |    800 |   576.41 | resolved | ABSENT                |
      | return-below-terminal       | 100.00 |    300 |   300 |   0.20 |    200 |    800 | -1000.00 | resolved | ABSENT                |
      | return-absent               | 100.00 |   1500 |   300 |   0.20 | ABSENT |    800 |   ABSENT | refused  | estimator_unavailable |
      | fast-fade                   | 100.00 |   1500 |   300 |   1.00 |   2500 |    800 |  1924.90 | resolved | ABSENT                |
      | slow-fade                   | 100.00 |   1500 |   300 |   0.10 |   2500 |    800 |  3609.74 | resolved | ABSENT                |
      | shrinking-issuer            | 100.00 |   -500 |   300 |   0.20 |   1200 |    800 |  1320.33 | resolved | ABSENT                |
      | base-cash-flow-absent       | ABSENT |   1500 |   300 |   0.20 |   2500 |    800 |   ABSENT | refused  | not_reported           |
      | growth-absent               | 100.00 | ABSENT |   300 |   0.20 |   2500 |    800 |   ABSENT | refused  | not_reported           |
      | discount-rate-absent        | 100.00 |   1500 |   300 |   0.20 |   2500 | ABSENT |   ABSENT | refused  | not_reported           |
      | non-fading-path             | 100.00 |   1500 |   300 |   0.00 |   2500 |    800 |   ABSENT | refused  | not_reported           |
      | terminal-growth-at-discount | 100.00 |   1500 |   800 |   0.20 |   2500 |    800 |   ABSENT | refused  | out_of_policy_range    |
      | terminal-growth-above-rate  | 100.00 |   1500 |   900 |   0.20 |   2500 |    800 |   ABSENT | refused  | out_of_policy_range    |
      | return-on-capital-zero      | 100.00 |   1500 |   300 |   0.20 |      0 |    800 |   ABSENT | refused  | out_of_policy_range    |
      | growth-outruns-the-fade     | 100.00 |  15000 |   300 |   0.01 |   2500 |    800 |   ABSENT | refused  | out_of_policy_range    |

  # Rows worth reading as a set:
  #
  #   flat-path                     Growth already at the terminal rate, so the path
  #                                 is flat and the answer is Gordon with the
  #                                 retention charge: 100 * (1 - 300/1200) / 0.05.
  #                                 Hand-computable, and it pins the arithmetic.
  #   high-return-compounder ..
  #   low-return-grower             Identical earnings and identical growth, ordered
  #                                 strictly by return on capital: 2624, 1924, 1250,
  #                                 576. This is FR-28. The forward lane in the old
  #                                 engine capitalized full earnings AND granted
  #                                 perpetual growth, which is why it priced the
  #                                 cohort at a median 1.5x market with the error
  #                                 rising as return on capital fell.
  #   value-neutral-return          At a return equal to the discount rate the
  #                                 retention charge exactly cancels the compounding,
  #                                 so value is earnings over the discount rate and
  #                                 the growth path stops mattering. That is a real
  #                                 return an issuer can earn.
  #   return-absent                 FR-29: an absent return is not the same fact as a
  #                                 measured return equal to the discount rate, so it
  #                                 does not get that (or any other) value. It refuses,
  #                                 named `estimator_unavailable` -- distinct from
  #                                 `not_reported` because nothing is missing from the
  #                                 filing here; the gap is in this Core's own
  #                                 evidence chain.
  #   return-below-terminal         An observed return below terminal growth is used
  #                                 as observed and goes negative. Flooring it at the
  #                                 cost of capital is what erased every distinction
  #                                 between sub-cost-of-capital issuers.
  #   fast-fade / slow-fade         The only surviving horizon quantity. 1925, 2624,
  #                                 3610 as the fade slows from 1.00 to 0.20 to 0.10
  #                                 per year -- continuous, with no branch anywhere.
  #                                 The old engine switched between a 5-year and a
  #                                 10-year projection and jumped.
  #   non-fading-path               A path that never reaches the terminal rate is a
  #                                 failed frame, refused at construction.
  #   terminal-growth-at-discount   FR-27 arithmetic guards. The integral does not
  #   terminal-growth-above-rate    converge and the retention charge does not
  #   return-on-capital-zero        divide, so there is no value to publish. Named
  #                                 as arithmetic: none carries an economic claim.
  #   growth-outruns-the-fade       150% growth fading at 0.01/year is 12+ units of
  #                                 excess compounding, past where the series can be
  #                                 summed to the asserted tolerance. Refusing beats
  #                                 publishing a number we cannot stand behind.
