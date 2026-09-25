Feature: Sector vs industry grouping for the FCF-yield benchmark (measured, not derived)

  `computeSectorBenchmarks` groups by the coarse GICS sector (e.g. "Utilities"), which
  mixes regulated utilities with independent power producers and diversified names that
  carry a structurally different FCF profile. That mixing was the leading hypothesis for
  why the app's Utilities FCF-yield centre (-6.7%, later -7.6% after Q1 shipped) reads
  more negative than the published Electric Utilities industry aggregate (-2.75%).

  This feature pins down what a real S&P 500 pull actually showed, so nobody re-proposes
  the industry-grouping fix on the strength of the same hypothesis without re-measuring.
  Per [[project_android_fcf_score_product_call_2026-08-23]] and the advisor's "measure
  first" verdict: no code change ships against this hypothesis until it is confirmed.

  Source: one-time lab pull, sp500 universe, AggressiveV5, `ScoreExport.kt` extended with
  `industry` and `fcf_yield_bps` columns, 497 scored rows,
  lab/data/score-export-sp500-aggressivev5.csv (2026-08-23).

  Background:
    Given the sp500 lab export dated 2026-08-23
    And "computeSectorBenchmarks" groups members by the GICS sector field
    And "MIN_SECTOR_MEMBERS" is 5

  Scenario: The Utilities sector is dominated by one industry, not evenly mixed

    Given the Utilities sector has 31 members with a measured fcf_yield_bps
    When the members are grouped by GICS industry
    Then "Utilities - Regulated Electric" has 23 members
    And "Utilities - Independent Power Producers" has 3 members
    And "Utilities - Diversified" has 2 members
    And "Utilities - Regulated Gas" has 2 members
    And "Utilities - Regulated Water" has 1 member
    And only "Utilities - Regulated Electric" clears the 5-member floor on its own

  Scenario: Splitting Utilities by industry does not move the FCF-yield centre

    Given the sector-level centre over all 31 Utilities members is -758 bps
    When the centre is recomputed over "Utilities - Regulated Electric" alone
    Then the industry-level centre is also -758 bps
    And the two centres are equal because the robust-centre MAD trim already
      discounts the Independent-Power-Producer and Diversified outliers
    And grouping by industry instead of sector narrows nothing for this sector

  Scenario: The sector/industry-mixing hypothesis is rejected by this measurement

    Given the industry-only centre for regulated utilities equals the sector-level centre
    When the -758 bps app centre is compared against the -275 bps published
      Electric Utilities industry aggregate
    Then the ~480 bps gap is not explained by sector-vs-industry grouping
    And no engine change groups sector benchmarks by industry on the strength of
      this hypothesis
    And the next candidate to measure is the Q1 equity-cap-only FCF-yield
      denominator on highly levered sectors, not sector/industry grouping

  # Rows worth reading as a set:
  #
  #   The two centre-equality assertions above are the whole finding. The
  #   industry split does not need a code change to be evaluated — the same
  #   robust-centre math the sector level already runs produces the same
  #   number when pointed at the dominant industry alone, because the trim
  #   step already removed the members a coarser industry split would have
  #   removed by grouping instead. A fix that groups by industry would spend
  #   real engineering cost narrowing a spread that measured data shows is
  #   already as narrow as the trim makes it.
  #
  #   PCG itself scored -1587 bps individually in this pull, well past the
  #   sector centre. That is a fact about PCG's own capital intensity, not
  #   evidence for or against the grouping hypothesis, and it is not double
  #   counted into either Scenario above.
