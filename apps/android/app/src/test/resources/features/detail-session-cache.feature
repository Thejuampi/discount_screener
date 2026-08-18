Feature: Detail session cache

  A second open of a warm ticker paints the last Detail. Disk, network, and
  leftover or dip assemble do not run again when the inputs are unchanged.
  Profile reset drops session flags.

  Scenario Outline: Detail paint after Back and Open

    Given the analyst is on the <surface>
    And ticker <symbol> is <warmth>
    When the analyst opens the ticker, goes Back, and opens it again
    Then the first frame <first_frame>
    And Back <back_state>

    Examples: session paint
      | case                              | surface  | symbol | warmth     | first_frame             | back_state    |
      | leftover_reopen_paints_session    | leftover | JPM    | warm       | paints_from_session     | hides_detail  |
      | oneshot_reopen_paints_session     | search   | EXPD   | warm       | paints_from_session     | hides_detail  |
      | cold_oneshot_has_no_session_paint | search   | EXPD   | cold       | shows_no_session_paint  | hides_detail  |
      | profile_switch_drops_session      | search   | EXPD   | warm_then_profile_switch | shows_no_session_paint | hides_detail |

  Scenario Outline: Warm repository skips repeat work

    Given ticker <symbol> is already loaded in memory
    When the analyst repeats <action>
    Then <repeat_cost> stays unchanged

    Examples: skip repeat IO
      | case                                   | symbol | action                 | repeat_cost            |
      | warm_detail_skips_disk_and_network     | AAPL   | ensure_detail          | disk_and_network       |
      | warm_replay_skips_network              | EXPD   | ensure_replay_backing  | replay_network         |
      | warm_adhoc_skips_quote_fetch           | SHOP   | ensure_detail          | quote_fetch            |
      | profile_switch_reloads_revision_history | AAPL  | switch_profile_and_open | revision_history_loads |

  Scenario Outline: Plan boards follow the input fingerprint

    Given leftover and dip boards were assembled once
    When <mutation> happens
    Then the next assemble <board_result>

    Examples: board memo
      | case                               | mutation           | board_result      |
      | leftover_unchanged_reuses_instance | no_input_change    | same_instance     |
      | leftover_price_change_rebuilds     | price_change       | new_instance      |
      | leftover_clear_drops_cache         | memory_reset       | new_instance      |
      | snapshot_reuses_leftover_and_dip   | second_snapshot    | same_instance     |
