Feature: Growth Posterior

  The Core fuses the Trailing Channel and the Forward Channel into a single
  Growth Posterior by inverse-variance weighting. Disagreement between channels
  widens nothing and never suppresses publication.

  Behaviour is added to this feature by adding a row to the table below. A new
  outline requires an entry in manifest.toml saying what no existing table
  covers (FR-44).

  Scenario Outline: Growth Posterior from two Evidence Channels

    Given a Trailing Channel of <g_tr> bps with variance <v_tr> over <n_tr> observations
    And a Forward Channel of <g_fw> bps with variance <v_fw> from <n_an> analysts
    When the Growth Posterior is resolved
    Then the point estimate is <g_hat> bps within 1 bp
    And the posterior variance is <v_hat> within 1 bp
    And the channel weights are <w_tr> and <w_fw> bps
    And the outcome is <outcome>

    # ABSENT is the reserved absence token required by FR-43. It is not zero,
    # not empty, and not a dash. tests/schema.rs rejects any other spelling.
    Examples: channel fusion
      | case                    | g_tr   | v_tr   | n_tr | g_fw   | v_fw   | n_an | g_hat  | v_hat  | w_tr  | w_fw  | outcome  |
      | both-tight-agree        |   1200 |   4000 |    5 |   1300 |   2250 |   22 |   1264 |   1440 |  3600 |  6400 | resolved |
      | both-tight-disagree     |   1200 |   4000 |    5 |   2600 |   2250 |   22 |   2096 |   1440 |  3600 |  6400 | resolved |
      | wide-consensus-pulled   |   1200 |   4000 |    5 |   2600 |  40000 |    4 |   1327 |   3636 |  9091 |   909 | resolved |
      | trailing-noisy          |   1200 |  90000 |    3 |   2600 |   2250 |   19 |   2566 |   2195 |   244 |  9756 | resolved |
      | forward-absent          |   1200 |   4000 |    5 | ABSENT | ABSENT |    0 |   1200 |   4000 | 10000 |     0 | resolved |
      | trailing-absent         | ABSENT | ABSENT |    0 |   2600 |   2250 |   19 |   2600 |   2250 |     0 | 10000 | resolved |
      | both-absent             | ABSENT | ABSENT |    0 | ABSENT | ABSENT |    0 | ABSENT | ABSENT |     0 |     0 | refused  |
      | contaminated-trailing   | ABSENT | ABSENT |    0 |   3110 |   3600 |   14 |   3110 |   3600 |     0 | 10000 | resolved |
      | negative-consensus      |   1200 |   4000 |    5 |   -800 |   2250 |   17 |    -80 |   1440 |  3600 |  6400 | resolved |
      | very-high-well-covered  |   2200 |   4000 |    7 |   2900 |   1600 |   26 |   2700 |   1143 |  2857 |  7143 | resolved |

  # Rows worth reading as a set, because the boundary is only visible across them:
  #
  #   both-tight-disagree      3x channel disagreement resolves and publishes. There is
  #                            no Disputed status in the Core (FR-15). Under the previous
  #                            engine this row is the entire nine-name refusal cluster.
  #   wide-consensus-pulled    26% consensus with wide analyst dispersion lands at 13.3%,
  #                            pulled by weighting, not by a 20% truncation (FR-13).
  #   very-high-well-covered   29% consensus with tight dispersion and 26 analysts lands
  #                            at 27%. The old ceiling would have cut it to 20%. This row
  #                            and the one above are the pair showing that the ceiling was
  #                            a proxy for dispersion all along.
  #   forward-absent           Falls back to the Trailing Channel exactly.
  #   contaminated-trailing    An acquisition-contaminated history is ABSENT, not zero
  #                            (FR-7). Under the previous engine this row is HPE, and it
  #                            read zero growth forever.
  #   both-absent              The only refusing row. Missing structural evidence, not
  #                            uncertainty (FR-32).
