Feature: Cost of Capital

  Cost of debt comes from a cross-sectionally fitted credit curve, so it rises
  with leverage and falls with interest coverage. WACC then falls at low
  leverage, where the tax shield dominates, and turns back up once the spread
  does — the U-shape the shipping engine cannot express, because its cost of
  debt is a trailing accounting coupon that rises with nothing.

  Behaviour is added to this feature by adding a row to a table below. A new
  outline requires an entry in manifest.toml saying what no existing table
  covers (FR-44).

  Scenario Outline: Cost of Debt from a fitted Credit Curve

    Given a Credit Curve with intercept <intercept> bps, leverage slope <b_lev> and coverage slope <b_cov>
    And an issuer with leverage <leverage> and interest coverage <coverage>
    And a risk-free rate of <rf> bps
    When the Cost of Debt is resolved
    Then the Cost of Debt is <r_d> bps within 1 bp
    And the outcome is <outcome>

    Examples: placing an issuer on the curve
      | case                | intercept | b_lev  | b_cov | leverage | coverage | rf  | r_d    | outcome  |
      | pristine-net-cash   |        90 |   0.45 |  0.05 |      0.0 |     30.0 | 469 |    489 | resolved |
      | investment-grade    |        90 |   0.45 |  0.05 |      1.0 |     12.0 | 469 |    546 | resolved |
      | mid-leverage        |        90 |   0.45 |  0.05 |      2.5 |      6.0 | 469 |    674 | resolved |
      | cable-like-leverage |        90 |   0.45 |  0.05 |      4.5 |      2.5 | 469 |   1071 | resolved |
      | distressed          |        90 |   0.45 |  0.05 |      7.0 |      1.2 | 469 |   2447 | resolved |
      | curve-unfitted      |    ABSENT | ABSENT | ABSENT|      2.5 |      6.0 | 469 | ABSENT | refused  |
      | non-monotone-fit    |        90 |  -0.20 |  0.05 |      2.5 |      6.0 | 469 | ABSENT | refused  |
      | flat-fit            |        90 |   0.00 |  0.05 |      2.5 |      6.0 | 469 | ABSENT | refused  |
      | leverage-absent     |        90 |   0.45 |  0.05 |   ABSENT |      6.0 | 469 | ABSENT | refused  |
      | coverage-absent     |        90 |   0.45 |  0.05 |      2.5 |   ABSENT | 469 | ABSENT | refused  |

  Scenario Outline: WACC from cost of equity and cost of debt

    Given a Cost of Equity of <coe> bps
    And a Cost of Debt of <r_d> bps
    And a debt weight of <w_d> bps and a marginal tax rate of <tax> bps
    When the WACC is resolved
    Then the WACC is <wacc> bps within 1 bp
    And the outcome is <outcome>

    Examples: weighting the two costs
      | case                     | coe    | r_d    | w_d  | tax  | wacc   | outcome  |
      | unlevered                |    925 |    489 |    0 | 2100 |    925 | resolved |
      | lightly-levered          |    925 |    546 | 2000 | 2100 |    826 | resolved |
      | wacc-trough              |    925 |    674 | 4000 | 2100 |    768 | resolved |
      | heavily-levered          |    925 |   1071 | 6000 | 2100 |    878 | resolved |
      | distressed-leverage      |    925 |   2447 | 8000 | 2100 |   1732 | resolved |
      | no-debt-and-no-curve     |    925 | ABSENT |    0 | 2100 |    925 | resolved |
      | debt-but-no-cost-of-debt |    925 | ABSENT | 4000 | 2100 | ABSENT | refused  |
      | cost-of-equity-absent    | ABSENT |    674 | 4000 | 2100 | ABSENT | refused  |
      | debt-weight-out-of-range |    925 |    674 |10001 | 2100 | ABSENT | refused  |

  # Rows worth reading as a set:
  #
  #   pristine-net-cash .. distressed   Cost of debt rises 489 -> 2447 bps across the
  #                                     leverage range. The shipping engine returns a
  #                                     trailing coupon here that rises with nothing.
  #   non-monotone-fit / flat-fit       FR-20 encoded as a refusal. A fit that does not
  #                                     rise with leverage is a FAILED fit, not a curve
  #                                     to use with a warning, so it cannot be built.
  #   lightly-levered .. distressed-leverage
  #                                     Read the wacc column down: 925, 826, 768, 878,
  #                                     1732. It falls, trough, then rises. The old
  #                                     engine only ever produced the falling half --
  #                                     CHTR at ~0.82 debt weight got 493 bps, below
  #                                     every unlevered name in the cohort.
  #   no-debt-and-no-curve              A net-cash issuer resolves with no credit curve
  #                                     at all. Missing evidence about a term carrying
  #                                     zero weight is not missing evidence about the
  #                                     answer.
  #   debt-but-no-cost-of-debt          The same absence, when the term does carry
  #                                     weight, refuses (FR-32).
