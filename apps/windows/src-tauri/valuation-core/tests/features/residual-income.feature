Feature: Residual Income Value

  A lender's balance sheet is the business, not the financing of it. There is no
  free cash flow to a firm whose debt is its raw material and no leverage ratio
  the credit curve could read, so financial issuers are valued on book plus the
  value they add beyond book — earning more on equity than equity costs, for as
  long as competition lets them.

  The result is an equity value already. Nothing on this path crosses a debt
  bridge.

  Behaviour is added to this feature by adding a row to the table below. A new
  outline requires an entry in manifest.toml saying what no existing table
  covers (FR-44).

  Scenario Outline: Residual Income on book for a financial issuer

    Given a book value of <book> with standard deviation 10.00
    And a return on equity of <roe> bps
    And a Growth Posterior of <g0> bps
    And a growth path fading to <g_inf> bps at <fade> per year
    And a cost of equity of <coe> bps
    When the Residual Income Value is resolved
    Then the Residual Income Value is <value> within 1 cent
    And the outcome is <outcome>
    And the absence reason is <reason>

    Examples: earning the cost of equity, or not
      | case                          | book     | roe    | g0     | g_inf | fade | coe    | value   | outcome  | reason                |
      | flat-book-earning-its-spread  |  1000.00 |   1600 |    300 |   300 | 0.20 |    800 | 1320.00 | resolved | ABSENT                |
      | value-neutral-return          |  1000.00 |    800 |    300 |   300 | 0.20 |    800 | 1000.00 | resolved | ABSENT                |
      | return-absent                 |  1000.00 | ABSENT |    300 |   300 | 0.20 |    800 |  ABSENT | refused  | estimator_unavailable |
      | below-the-cost-of-equity      |  1000.00 |    400 |    300 |   300 | 0.20 |    800 |  840.00 | resolved | ABSENT                |
      | growing-franchise             |  1000.00 |   1600 |   1000 |   300 | 0.20 |    800 | 1375.61 | resolved | ABSENT                |
      | fast-fade                     |  1000.00 |   1600 |   1000 |   300 | 1.00 |    800 | 1078.85 | resolved | ABSENT                |
      | slow-fade                     |  1000.00 |   1600 |   1000 |   300 | 0.10 |    800 | 1717.84 | resolved | ABSENT                |
      | shrinking-book                |  1000.00 |   1600 |   -200 |   300 | 0.20 |    800 | 1287.03 | resolved | ABSENT                |
      | book-absent                   |   ABSENT |   1600 |    300 |   300 | 0.20 |    800 |  ABSENT | refused  | not_reported           |
      | growth-absent                 |  1000.00 |   1600 | ABSENT |   300 | 0.20 |    800 |  ABSENT | refused  | not_reported           |
      | cost-of-equity-absent         |  1000.00 |   1600 |    300 |   300 | 0.20 | ABSENT |  ABSENT | refused  | not_reported           |
      | insolvent-book                | -1000.00 |   1600 |    300 |   300 | 0.20 |    800 |  ABSENT | refused  | out_of_policy_range    |
      | book-outgrows-its-return      |  1000.00 |    600 |    900 |   300 | 0.20 |    800 |  ABSENT | refused  | out_of_policy_range    |
      | non-fading-path               |  1000.00 |   1600 |    300 |   300 | 0.00 |    800 |  ABSENT | refused  | not_reported           |
      | terminal-growth-at-the-cost   |  1000.00 |   1600 |    300 |   800 | 0.20 |    800 |  ABSENT | refused  | out_of_policy_range    |
      | growth-outruns-the-fade       |  1000.00 |  20000 |  15000 |   300 | 0.01 |    800 |  ABSENT | refused  | out_of_policy_range    |

  # Rows worth reading as a set:
  #
  #   flat-book-earning-its-spread  Hand-computable and it pins the arithmetic. On a
  #                                 flat path the series is one term, 1/(a + k) with
  #                                 a = 0.08 - 0.03 and k = 0.20, so it is 4. The
  #                                 spread is 0.16 - 0.08 = 0.08, the multiple is
  #                                 1 + 0.08 * 4 = 1.32, and book of 1000 is 1320.00
  #                                 exactly.
  #   value-neutral-return          Earning exactly the cost of equity adds nothing
  #                                 to book, whatever growth does -- the FR-29
  #                                 identity in its residual-income form. That is a
  #                                 real return a balance sheet can earn.
  #   return-absent                 An absent return does not get that (or any other)
  #                                 value -- "we do not know whether this balance
  #                                 sheet earns its keep" is not the same statement as
  #                                 "we measured break-even". It refuses, named
  #                                 `estimator_unavailable`.
  #   below-the-cost-of-equity      Not floored at book. A bank destroying equity
  #                                 capital is worth less than the capital, and the
  #                                 spread is what says so.
  #   growing-franchise             More book to earn the spread on: 1375.61 against
  #                                 the flat path's 1320.00, same return, same cost.
  #   fast-fade / slow-fade         1078.85, 1375.61, 1717.84 as the fade slows from
  #                                 1.00 to 0.20 to 0.10 per year. The fade rate
  #                                 governs both halves at once -- book stops growing
  #                                 AND the spread stops being earned -- because a
  #                                 competitive advantage eroding is one event, not
  #                                 two, and fitting it twice would invent a
  #                                 parameter the cross-section cannot estimate.
  #   shrinking-book                A shrinking balance sheet still earns its spread
  #                                 on what is left of it, and 1287.03 is below the
  #                                 flat path rather than below book.
  #   insolvent-book                Residual income measures value added *to* book.
  #                                 Scaling a spread by a negative base returns a
  #                                 number with the sign back to front, so this is
  #                                 out of policy range rather than a valuation.
  #   book-outgrows-its-return      Under clean surplus, book grows out of retained
  #                                 earnings. Growth above the return that finances
  #                                 it is growth funded by issuing shares, and the
  #                                 value it creates would be split with owners who
  #                                 do not exist yet. Refused rather than credited to
  #                                 today's shareholders.
  #   terminal-growth-at-the-cost   FR-27 arithmetic guards, the same two the
  #   growth-outruns-the-fade       operating path carries: the integral has to
  #                                 converge and the series has to be summable to the
  #                                 asserted tolerance.
