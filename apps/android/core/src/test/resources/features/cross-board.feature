Feature: Cross board
  The Plans Cross hunt lists names at or slightly after a MACD golden cross.

  Scenario Outline: Freshness window
    Given a Cross-eligible name with F and Street 20%
    And the MACD histogram crossed <bars> closed daily bars ago
    And flipped_bars_max is <max>
    When the Cross hunter classifies the name
    Then the lane is <lane>

    Examples:
      | Case                | bars | max | lane |
      | at_cross            | 0    | 3   | Now  |
      | three_bars          | 3    | 3   | Now  |
      | four_bars           | 4    | 3   | Out  |
      | three_bars_max_two  | 3    | 2   | Out  |
      | three_bars_max_four | 3    | 4   | Now  |

  Scenario Outline: Hard gates
    Given a name whose MACD histogram crossed 0 bars ago
    And flipped_bars_max is 3
    And F is <f>
    And Street bps is <street>
    And histogram slope is <slope>
    And RSI is <rsi>
    When the Cross hunter classifies the name
    Then the lane is <lane>

    Examples:
      | Case           | f    | street | slope | rsi | lane   |
      | complete_and   | 20   | 3000   | 8.0   | 40  | Now    |
      | street_almost  | 20   | 1600   | 8.0   | 40  | Almost |
      | street_low     | 20   | 1400   | 8.0   | 40  | Out    |
      | missing_f      | null | 3000   | 8.0   | 40  | Out    |
      | fading_hist    | 20   | 3000   | -4.0  | 40  | Out    |
      | rsi_hot        | 20   | 3000   | 8.0   | 62  | Out    |
      | still_negative | 20   | 3000   | 8.0   | 40  | Out    |
