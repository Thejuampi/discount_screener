Feature: Profile switch cancels previous-profile work

  A profile switch must drop the previous universe's Yahoo fetches.
  A later switch must keep the new profile's refresh alive.
  Coroutine cancel must abort a Yahoo retry wait.

  Scenario Outline: Profile switch stops previous-profile work

    Given the dashboard is on <from_profile>
    And the previous refresh is still in flight
    When the analyst switches to <to_profile>
    Then Yahoo <yahoo_rule>
    And the Opportunities fill <fill_rule>

    Examples: cancel gate
      | case                                      | from_profile | to_profile | yahoo_rule                         | fill_rule                   |
      | stale_generation_does_not_fetch           | dow          | qa         | fetches_no_prior_exclusive_symbols | may_use_cache               |
      | second_switch_does_not_cancel_new_refresh | dow          | qa         | keeps_current_profile_fetches      | current_exclusive_rows_live |
      | cancelled_refresh_does_not_journal        | qa           | dow        | no_stale_score_journal             | may_use_cache               |
      | chart_cancel_aborts_symbol                | qa           | qa         | chart_cancel_stops_apply           | exclusive_row_not_live      |
      | cancelled_request_does_not_retry_sleep    | qa           | dow        | cancel_aborts_retry_wait           | may_use_cache               |
      | socket_timeout_is_not_treated_as_cancel   | qa           | qa         | timeout_stays_io                   | may_use_cache               |
      | live_refresh_paints_without_silence_gate  | qa           | qa         | fetches_continue                   | paints_before_300ms_silence |
      | stale_market_read_does_not_write_new_profile | qa        | dow        | no_stale_market_write              | may_use_cache               |
      | cancelled_detail_open_is_not_ticker_unavailable | qa    | dow        | cancel_aborts_detail               | no_ticker_unavailable       |
      | invalidate_clears_a_fresh_cache             | qa           | dow        | market_cache_is_cleared            | new_market_read_runs        |
      | stale_enrichment_does_not_steal_job         | qa           | dow        | no_new_prior_timeseries            | may_use_cache               |
      | cancelled_request_aborts_body_read          | qa           | dow        | cancel_aborts_body_read            | may_use_cache               |
      | cancelled_market_read_does_not_complete_old_fetch | qa    | dow        | cancel_aborts_stale_market_fetch   | may_use_cache               |
      | cancelled_cnn_body_read_aborts              | qa           | dow        | cancel_aborts_cnn_body_read        | may_use_cache               |
      | cancelled_crumb_aborts_body_read            | qa           | dow        | cancel_aborts_crumb_body_read      | may_use_cache               |
      | stale_warm_start_adopt_does_not_wipe_new_profile | qa     | dow        | new_profile_stays_adopted          | may_use_cache               |
