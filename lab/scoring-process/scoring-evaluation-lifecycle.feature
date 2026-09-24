@reconciled_draft
Feature: Durable scoring acquisition and point-in-time evaluation

  Android can suspend or terminate the process without warning.
  Acquisition progress survives independently from scientific observations.
  Archived evidence survives independently from successful scoring.
  A committed experiment never mixes dates, contexts, models, or manifests.
  These scenarios specify behavior. Android step definitions do not exist yet.

  Rule: Android lifecycle events do not invent durable progress

    Scenario Outline: The app leaves the foreground
      Given acquisition is running with a durable checkpoint
      When Android performs <lifecycle_event>
      Then the durable acquisition state is <durable_state>
      And the in-memory runner is <runner_result>

      Examples: lifecycle boundaries
        | case                       | lifecycle_event       | durable_state       | runner_result        |
        | activity_stops            | activity_stop         | unchanged           | may_continue         |
        | activity_is_recreated     | activity_recreation   | unchanged           | attach_only_if_invocation_live |
        | process_is_suspended      | process_suspension    | unchanged           | frozen_in_memory     |
        | process_dies              | process_termination   | last_checkpoint     | lost                 |
        | device_reboots            | device_reboot         | last_checkpoint     | lost                 |

  Rule: Reopening reconciles acquisition and observation separately

    Scenario Outline: A new process opens the profile
      Given acquisition was <acquisition_state>
      And observation was <observation_state>
      And the observation deadline is <deadline_state>
      And the scientific context is <context_state>
      When a new process reconciles both records
      Then acquisition becomes <acquisition_result>
      And observation becomes <observation_result>
      And the fairness cursor is <cursor_result>

      Examples: independent reconciliation
        | case                               | acquisition_state              | observation_state | deadline_state | context_state | acquisition_result                  | observation_result | cursor_result |
        | same_session_reclaims_both         | running_enabled_selected       | committed_computing | future       | same          | interrupted_enabled_selected       | resumable_original | retained      |
        | two_month_gap_keeps_commitment     | running_enabled_selected       | committed_computing | reached      | same          | interrupted_enabled_selected       | resumable_original | retained      |
        | formula_change_invalidates_candidate| running_enabled_selected      | frozen            | future         | changed       | interrupted_enabled_selected       | invalidated        | retained      |
        | formula_change_keeps_commitment    | running_enabled_selected       | committed         | reached        | changed       | interrupted_enabled_selected       | resumable_original | retained      |
        | manual_pause_survives_two_months   | pending_user_paused_selected   | open              | reached        | same          | pending_user_paused_selected       | expired            | retained      |
        | unselected_profile_stays_disabled  | pending_enabled_unselected     | interrupted_open  | future         | same          | pending_enabled_unselected         | interrupted        | retained      |

  Rule: The two-month restart creates a new scientific attempt

    Scenario Outline: Reopen after the original opening
      Given the old attempt has <old_artifact>
      And its target opening passed two months ago
      When the app starts a March observation
      Then the old artifact is <old_result>
      And cached revisions are <cache_result>
      And March uses <march_result>

      Examples: old and reusable records
        | case                         | old_artifact          | old_result           | cache_result                | march_result          |
        | open_attempt_expires         | open_attempt          | expired              | revalidated                 | new_attempt           |
        | frozen_manifest_expires      | frozen_manifest       | publication_rejected | revisions_revalidated       | new_manifest          |
        | committed_partial_outputs_resume| committed_partial_outputs | retained_for_late_materialization | original_inputs_pinned | original_models_resume |
        | committed_capsule_survives   | committed_capsule     | retained              | original_inputs_pinned      | separate_new_attempt  |
        | stale_input_is_not_renamed   | stale_input_revision  | retained_as_history  | unavailable_until_refreshed | original_source_time  |
        | valid_filing_can_be_reused   | valid_filing_revision | retained_as_history  | eligible_if_policy_accepts  | original_source_time  |

  Rule: Explicit pause differs from operating-system suspension

    Scenario Outline: Work is paused
      Given acquisition receives <pause_source>
      When time passes beyond the observation opening
      Then acquisition becomes <acquisition_result>
      And continuation requires <resume_requirement>
      And an old uncommitted observation becomes expired

      Examples: pause meanings
        | case                        | pause_source        | acquisition_result          | resume_requirement  |
        | user_pause_persists         | explicit_user_pause | control_user_paused         | explicit_resume     |
        | system_suspension_is_silent | process_suspension  | unchanged                   | process_survival    |
        | process_death_interrupts    | process_termination | progress_interrupted_on_open| automatic_reclaim   |
        | profile_switch_disables     | profile_switch      | selection_unselected        | profile_reselection |

  Rule: Profile selection cannot remove a manual pause

    Scenario Outline: Change profiles around a manual pause
      Given acquisition has <initial_state>
      When the coordinator applies <event_sequence>
      Then acquisition has <final_state>
      And a claim is <claim_result>

      Examples: independent control dimensions
        | case                           | initial_state                | event_sequence                          | final_state                   | claim_result             |
        | pause_then_switch_and_return   | pending_user_paused_selected | unselect_then_select                    | pending_user_paused_selected  | blocked_until_resume     |
        | switch_pause_then_return       | pending_enabled_selected     | unselect_pause_then_select              | pending_user_paused_selected  | blocked_until_resume     |
        | resume_while_unselected        | pending_user_paused_selected | unselect_then_resume                    | pending_enabled_unselected    | blocked_until_reselected |
        | resume_and_reselect            | pending_user_paused_selected | unselect_resume_then_select             | pending_enabled_selected      | allowed                  |

  Rule: Refresh requests coalesce without losing new demand

    Scenario Outline: Another request arrives
      Given the job is <job_state>
      And requested revision is <old_revision>
      When <request_kind> occurs
      Then the job count is <job_count>
      And requested revision becomes <new_revision>
      And the request result is <request_result>

      Examples: bounded durable demand
        | case                           | job_state                    | old_revision | request_kind    | job_count | new_revision | request_result          |
        | tap_during_running_coalesces   | running_enabled_selected     | 7            | refresh_request | 1         | 8            | demand_updated          |
        | tap_during_pending_coalesces   | pending_enabled_selected     | 7            | refresh_request | 1         | 8            | demand_updated          |
        | tap_during_pause_is_remembered | pending_user_paused_selected | 7            | refresh_request | 1         | 8            | remembered_while_paused |
        | activity_recreation_attaches   | running_enabled_selected     | 7            | observer_attach | 1         | 7            | observer_attached       |
        | foreground_return_attaches     | running_enabled_selected     | 7            | observer_attach | 1         | 7            | observer_attached       |

  Rule: A checkpoint cannot fulfill a newer request

    Scenario Outline: A claimed slice checkpoints
      Given the job durably claimed revision <durable_claimed_revision>
      And the slice reports revision <result_claimed_revision>
      And current requested revision is <requested_revision>
      And previous fulfilled revision is <old_fulfilled_revision>
      And required work is <work_status>
      When its authorized checkpoint commits
      Then fulfilled revision becomes <new_fulfilled_revision>
      And the job becomes <job_result>

      Examples: authorization, progress, and fulfillment
        | case                          | durable_claimed_revision | result_claimed_revision | requested_revision | old_fulfilled_revision | work_status | new_fulfilled_revision | job_result          |
        | partial_current_stays_pending | 7                        | 7                       | 7                  | 6                      | remaining   | 6                      | pending             |
        | complete_current_finishes     | 7                        | 7                       | 7                  | 6                      | complete    | 7                      | idle                |
        | newer_request_stays_pending   | 7                        | 7                       | 8                  | 6                      | complete    | 7                      | pending             |
        | partial_with_newer_request    | 7                        | 7                       | 8                  | 6                      | remaining   | 6                      | pending             |
        | wrong_claim_is_rejected       | 7                        | 6                       | 8                  | 6                      | complete    | unchanged              | checkpoint_rejected |
        | fulfillment_never_decreases   | 7                        | 7                       | 7                  | 7                      | complete    | 7                      | idle                |

  Rule: Every write uses complete fencing identity

    Scenario Outline: A result tries to write
      Given the result has <identity_relation>
      And the durable record is <record_state>
      When the result tries to commit
      Then the commit is <commit_result>

      Examples: fenced writers
        | case                   | identity_relation   | record_state | commit_result             |
        | exact_identity_commits | all_equal           | running      | accepted                  |
        | wrong_key_drops        | job_key_differs     | running      | rejected_wrong_key        |
        | wrong_process_drops    | process_differs     | running      | rejected_stale_process    |
        | stopped_invocation_drops| invocation_differs | running      | rejected_stale_invocation |
        | old_generation_drops   | generation_differs  | running      | rejected_stale_generation |
        | changed_context_drops  | context_differs     | running      | rejected_changed_context  |
        | paused_job_drops       | all_equal           | paused       | rejected_wrong_state      |
        | unknown_identity_drops | non_ground_identity | running      | rejected_non_ground_input |

  Rule: SQLite transactions define process-death boundaries

    Scenario Outline: Android kills the process
      Given termination occurs <termination_point>
      When the next process reads SQLite
      Then stored inputs are <input_result>
      And the cursor is <cursor_result>
      And cohort publication is <cohort_result>

      Examples: atomic boundaries
        | case                 | termination_point         | input_result       | cursor_result     | cohort_result |
        | before_checkpoint    | before_checkpoint         | previous_only      | previous_position | absent        |
        | during_checkpoint    | inside_checkpoint         | all_or_previous    | matching_inputs   | absent        |
        | after_checkpoint     | after_checkpoint          | new_inputs_present | next_position     | absent        |
        | during_cohort_commit | inside_cohort_transaction | unchanged          | unchanged         | all_or_absent |
        | after_materialization| after_materialization_tx   | unchanged          | unchanged         | materialized_once |

  Rule: Durable dispatch prevents repeated first-item starvation

    Scenario Outline: The process dies around item dispatch
      Given the scheduler selects the least recently attempted item
      When termination occurs <termination_point>
      Then the dispatch record is <dispatch_result>
      And the next selection is <selection_result>

      Examples: dispatch boundaries
        | case                         | termination_point          | dispatch_result               | selection_result                 |
        | death_before_dispatch        | before_dispatch_transaction | absent                        | same_item_may_return             |
        | death_during_dispatch        | inside_dispatch_transaction | all_or_absent                 | matches_committed_dispatch       |
        | death_after_dispatch         | after_dispatch_before_call   | interrupted_attempt_on_reopen | another_item_then_retry_later    |
        | death_during_network_call    | after_call_before_checkpoint | interrupted_attempt_on_reopen | another_item_then_retry_later    |
        | response_checkpointed        | after_result_checkpoint      | completed_attempt             | advances_fairness_order          |

  Rule: Dispatch reconciliation uses the complete claim token

    Scenario Outline: Reconcile an outstanding dispatch
      Given the dispatch token is <dispatch_token>
      And the current claim token is <current_token>
      And invocation liveness is <invocation_state>
      When dispatch reconciliation runs
      Then the dispatch becomes <dispatch_result>

      Examples: process, invocation and generation fencing
        | case                         | dispatch_token             | current_token              | invocation_state | dispatch_result         |
        | exact_live_claim_attaches    | p1_i1_gen7_rev7            | p1_i1_gen7_rev7            | live             | attached                |
        | same_process_worker_stopped  | p1_i1_gen7_rev7            | p1_i1_gen7_rev7            | stopped          | interrupted_and_rotated |
        | process_death_rotates        | p1_i1_gen7_rev7            | no_claim                    | stopped          | interrupted_and_rotated |
        | new_process_rotates          | p1_i1_gen7_rev7            | p2_i2_gen8_rev7            | live             | interrupted_and_rotated |
        | same_process_new_invocation  | p1_i1_gen7_rev7            | p1_i2_gen8_rev7            | live             | interrupted_and_rotated |
        | unknown_liveness_blocks      | p1_i1_gen7_rev7            | p1_i1_gen7_rev7            | unknown          | blocked                 |

  @model @integration_required
  Rule: A late response has archive authority only

    Scenario Outline: A response arrives after runner fencing
      Given the response links to <dispatch_proof>
      And archive authority is <archive_authority>
      And delivery compatibility is <delivery_relation>
      And receipt time is <receipt_time>
      And manifest state is <manifest_state>
      When late ingestion runs
      Then archive ingestion is <archive_result>
      And runner authority remains revoked
      And jobs, cache, manifests and scores remain unchanged

      Examples: immutable late ingestion
        | case                         | dispatch_proof      | archive_authority | delivery_relation    | receipt_time        | manifest_state     | archive_result                    |
        | useful_late_revision         | original_dispatch   | archive_granted   | compatible_new       | actual_receipt_time | frozen_manifest    | append_archive_only               |
        | duplicate_late_delivery      | original_dispatch   | archive_granted   | compatible_duplicate | actual_receipt_time | committed_manifest | idempotent_archive_noop           |
        | incompatible_delivery        | original_dispatch   | archive_granted   | incompatible         | actual_receipt_time | no_manifest        | quarantine_without_mutable_effects|
        | wrong_dispatch               | mismatched_dispatch | archive_granted   | compatible_new       | actual_receipt_time | no_manifest        | reject_dispatch_mismatch          |
        | authority_was_revoked        | original_dispatch   | archive_revoked   | compatible_new       | actual_receipt_time | no_manifest        | reject_archive_revoked            |
        | evidence_was_erased          | original_dispatch   | erasure_tombstoned| compatible_new       | actual_receipt_time | no_manifest        | reject_without_reinsertion        |
        | receipt_time_is_missing      | original_dispatch   | archive_granted   | compatible_new       | missing             | no_manifest        | reject_missing_actual_time        |

    Scenario: Quarantine preserves evidence metadata only
      Given a response links to its original durable dispatch
      And its delivery is incompatible
      And its actual receipt time is known
      When late ingestion quarantines the response
      Then the actual receipt time remains recorded
      And runner authority remains revoked
      And a frozen manifest remains unchanged

    Scenario: Late evidence never enters a frozen manifest
      Given a manifest froze before a late response arrived
      When the response is accepted into the immutable archive
      Then its actual observation and receipt times are preserved
      And the frozen manifest remains byte-identical
      And only a later prospective experiment can consider that revision

  Rule: Input eligibility uses each temporal meaning

    Scenario Outline: Freeze one input revision
      Given observed time is <observed_relation>
      And source publication time is <publication_relation>
      And the family availability policy is <availability_policy>
      And source age is <source_age>
      And contracts are <contract_relation>
      And membership is <membership_relation>
      When the observation freezes at the cut
      Then the input decision is <decision>

      Examples: availability, age and structure
        | case                             | observed_relation | publication_relation  | availability_policy         | source_age   | contract_relation | membership_relation | decision                               |
        | local_observation_is_sufficient  | before_cut        | unknown               | local_observation_sufficient| accepted     | compatible        | member              | eligible_publication_unknown           |
        | strict_family_needs_publication  | before_cut        | unknown               | publication_required        | accepted     | compatible        | member              | unavailable_publication_time_unknown   |
        | known_publication_is_eligible    | before_cut        | before_cut            | publication_required        | accepted     | compatible        | member              | eligible_publication_known             |
        | observation_after_cut_rejects    | after_cut         | before_cut            | local_observation_sufficient| accepted     | compatible        | member              | unavailable_observed_after_cut         |
        | publication_after_cut_rejects    | before_cut        | after_cut             | local_observation_sufficient| accepted     | compatible        | member              | unavailable_publication_after_cut      |
        | local_fetch_never_rejuvenates    | before_cut        | unknown               | local_observation_sufficient| expired      | compatible        | member              | unavailable_source_age_expired         |
        | unknown_age_remains_unknown      | before_cut        | before_cut            | publication_required        | unknown      | compatible        | member              | unavailable_source_age_unknown         |
        | source_contract_change_rejects   | before_cut        | before_cut            | publication_required        | accepted     | source_changed    | member              | unavailable_source_incompatible        |
        | removed_symbol_is_rejected       | before_cut        | before_cut            | publication_required        | accepted     | compatible        | not_member          | unavailable_outside_universe           |

    Scenario: Prospective availability does not prove historical reconstruction
      Given a current-only value was observed before a prospective cut
      And its original publication time is unknown
      When the same value is requested for an earlier unobserved date
      Then prospective eligibility remains recorded for its actual observation
      And historical reconstruction is unavailable
      And the source age is not changed by later fetches

    Scenario Outline: Equal wall-clock times use durable sequence order
      Given the input and cut share one wall-clock timestamp
      And input archive sequence is <sequence_relation>
      When the manifest transaction freezes its visible sequence
      Then the input is <eligibility>

      Examples: sequence resolves equal timestamps
        | case                    | sequence_relation       | eligibility             |
        | input_already_visible   | before_manifest_sequence| eligible                |
        | input_committed_later   | after_manifest_sequence | unavailable_after_cut   |

  Rule: Corrections and horizons do not alter knowledge time

    Scenario Outline: Interpret a provider revision
      Given the source represents <represented_time>
      And the app first observes it <observation_time>
      When a cohort with an earlier cut is evaluated
      Then the revision is <revision_result>

      Examples: distinct source times
        | case                       | represented_time          | observation_time | revision_result              |
        | future_forecast_known_now  | future_forecast_horizon   | before_cut       | eligible_if_other_rules_pass |
        | old_filing_known_now       | old_accounting_period     | before_cut       | eligible_if_fresh            |
        | old_period_corrected_later | old_accounting_period     | after_cut        | excluded_from_old_cohort     |
        | target_revised_later       | existing_forecast_horizon | after_cut        | excluded_from_old_cohort     |

  Rule: One manifest controls every model output

    Scenario Outline: Resume scoring after interruption
      Given the frozen manifest is <manifest_id>
      And stored outputs are <output_state>
      When scoring reconciliation runs
      Then the output action is <output_action>

      Examples: manifest consistency
        | case                         | manifest_id | output_state          | output_action                 |
        | no_outputs_resume            | m7          | none                  | compute_missing_from_m7       |
        | partial_same_manifest_resume | m7          | v1_v2_from_m7         | compute_missing_from_m7       |
        | complete_same_manifest_ready | m7          | v1_to_v5_from_m7      | ready_to_materialize           |
        | mixed_manifests_restart      | m7          | v1_from_m7_v5_from_m8 | discard_outputs_and_recompute |
        | live_context_change_is_ignored| m7         | v1_to_v5_from_m7      | preserve_committed_context    |

  @model @integration_required
  Rule: Only a complete pre-cut commitment survives the cutoff

    Scenario Outline: Reserve the canonical experiment
      Given candidate state is <candidate_state>
      And capsule completeness is <capsule_state>
      And commitment time is <commitment_time>
      And canonical reservation is <reservation_state>
      When the commitment transaction runs
      Then commitment becomes <commitment_result>

      Examples: commitment boundaries
        | case                         | candidate_state | capsule_state | commitment_time | reservation_state   | commitment_result                 |
        | complete_candidate_commits   | frozen          | complete      | before_cut      | absent              | commit_and_reserve                |
        | formula_name_is_not_enough   | frozen          | incomplete    | before_cut      | absent              | reject_not_fully_frozen           |
        | exact_cut_is_too_late        | frozen          | complete      | at_cut          | absent              | reject_commitment_too_late        |
        | after_cut_is_too_late        | frozen          | complete      | after_cut       | absent              | reject_commitment_too_late        |
        | second_candidate_loses       | frozen          | complete      | before_cut      | different_commitment| superseded_existing_commitment    |
        | lost_ack_reads_original      | committed       | complete      | after_cut       | same_commitment     | read_original_commitment          |

    Scenario Outline: Reconcile after the cutoff
      Given the saved phase is <saved_phase>
      When the cutoff passed two months ago
      Then the experiment is <result>

      Examples: expiry and survival
        | case                    | saved_phase           | result                         |
        | open_candidate_expires  | open                  | expired                        |
        | frozen_candidate_expires| frozen                | expired                        |
        | commitment_survives     | committed             | resume_original_commitment     |
        | computation_survives    | computing             | resume_original_commitment     |
        | ready_survives          | ready                 | materialize_original_late      |

  @model @integration_required
  Rule: Late materialization never claims on-time publication

    Scenario Outline: Materialize all model outputs
      Given commitment proof is <commitment_proof>
      And materialization time is <materialization_time>
      And outputs are <output_state>
      And existing result is <existing_result>
      When publication runs atomically
      Then classification is <classification>

      Examples: immutable timing classification
        | case                       | commitment_proof    | materialization_time | output_state            | existing_result  | classification                         |
        | published_before_cut       | committed_before_cut| before_cut           | complete_same_manifest  | absent           | PUBLISHED_ON_TIME                       |
        | finished_exactly_at_cut    | committed_before_cut| at_cut               | complete_same_manifest  | absent           | MATERIALIZED_LATE                       |
        | finished_months_later      | committed_before_cut| after_cut            | complete_same_manifest  | absent           | MATERIALIZED_LATE                       |
        | no_pre_cut_commitment      | absent              | after_cut            | complete_same_manifest  | absent           | reject_no_valid_pre_cut_commitment      |
        | mixed_outputs_reject       | committed_before_cut| after_cut            | mixed_manifests         | absent           | reject_incomplete_or_mixed_outputs      |
        | retry_preserves_original   | committed_before_cut| after_cut            | complete_same_manifest  | same_result      | original_classification_preserved       |
        | conflicting_retry_rejects  | committed_before_cut| after_cut            | complete_same_manifest  | different_result | reject_immutable_result_conflict        |

  Rule: Fair scheduling survives short sessions

    Scenario Outline: Select the next acquisition item
      Given previous slices have <history_state>
      And another item is <other_state>
      When the scheduler selects eligible work
      Then selection <selection_result>

      Examples: persistent fairness
        | case                            | history_state            | other_state    | selection_result        |
        | success_advances                | success                  | waiting        | moves_forward           |
        | terminal_failure_advances       | recorded_terminal_failure| waiting        | moves_forward           |
        | transient_retry_waits           | retry_time_future        | waiting        | chooses_waiting_item    |
        | least_recent_attempt_breaks_tie | equal_priority           | least_recent   | chooses_least_recent    |
        | repeated_openings_keep_cursor   | several_short_sessions   | never_attempted| chooses_never_attempted |
        | refresh_does_not_reset_order    | new_request_revision     | long_waiting   | keeps_fairness_order    |
        | interrupted_dispatch_rotates    | interrupted_attempt      | never_attempted| chooses_never_attempted |

  Rule: Unknown time prevents scientific publication

    Scenario Outline: Clock or calendar confidence fails
      Given time confidence is <time_state>
      When the app requests observation and acquisition
      Then acquisition is <acquisition_result>
      And observation is <observation_result>

      Examples: time authority
        | case                        | time_state              | acquisition_result | observation_result       |
        | trusted_time_allows_both    | trusted                 | allowed            | allowed                  |
        | missing_calendar_blocks_cut | calendar_unavailable    | allowed            | unavailable_with_reason  |
        | clock_rollback_blocks_cut   | clock_rollback_detected | allowed            | unavailable_with_reason  |
        | timezone_change_uses_utc    | timezone_changed        | allowed            | unchanged_if_utc_trusted |

  Rule: External failures stay explicit

    Scenario Outline: An external dependency fails
      Given the failure is <failure_kind>
      When the current slice handles it
      Then durable work becomes <work_result>
      And scientific evidence becomes <evidence_result>

      Examples: typed failures
        | case                    | failure_kind     | work_result              | evidence_result         |
        | network_offline        | offline          | pending_with_retry       | missing_with_reason     |
        | provider_rate_limit    | rate_limited     | pending_until_retry      | missing_with_reason     |
        | database_full          | storage_full     | stopped_without_loss     | no_new_cohort           |
        | corrupt_cache_revision | corrupt_cache    | reacquire_item           | unavailable_with_reason |
        | corrupt_manifest       | corrupt_manifest | inputs_retained          | attempt_cannot_commit   |
        | incompatible_app_update| schema_migration | compatible_rows_retained | old_attempt_invalidated |

  @model @integration_required
  Rule: Durability requirements remain separate from mechanisms and cost

    Scenario Outline: Select an evidence durability profile
      Given evidence class is <evidence_class>
      And storage mechanism is <mechanism>
      And driver verification is <driver_state>
      And measured cost is <measurement_state>
      When evidence writes are enabled
      Then durability is <durability_result>

      Examples: measured durability boundary
        | case                         | evidence_class         | mechanism           | driver_state      | measurement_state     | durability_result                 |
        | full_verified_and_acceptable | irreplaceable_evidence | WAL_FULL             | verified          | acceptable            | requirement_met                   |
        | full_cost_not_measured       | irreplaceable_evidence | WAL_FULL             | verified          | not_measured          | block_measurement_required        |
        | full_driver_not_verified     | irreplaceable_evidence | WAL_FULL             | unverified        | acceptable            | block_driver_not_verified         |
        | normal_is_not_equivalent     | irreplaceable_evidence | WAL_NORMAL           | verified          | acceptable            | block_requirement_not_met         |
        | slow_full_needs_design_work  | irreplaceable_evidence | WAL_FULL             | verified          | unacceptable          | block_performance_requirement     |
        | proven_alternative_can_pass  | irreplaceable_evidence | alternative_proven   | verified          | acceptable            | requirement_met                   |
        | unproven_alternative_blocks  | irreplaceable_evidence | alternative_unproven | verified          | acceptable            | block_requirement_not_met         |

    Scenario: WAL FULL is a reference mechanism, not a performance claim
      Given WAL FULL is the reference for irreplaceable evidence
      When supported phones are measured
      Then startup, commit latency, throughput and battery cost are recorded
      And a faster mechanism cannot replace it without equal durability evidence

  @model @integration_required
  Rule: Daily outcomes preserve three different quantities

    Scenario Outline: Add one daily trajectory point
      Given trajectory window is <window>
      And price evidence is <price_state>
      And the frozen original target is <original_target>
      And later target state is <target_revision>
      And calendar state is <calendar_state>
      When the daily outcome is classified
      Then available measures are <available_measures>
      And unavailable measures are <unavailable_measures>

      Examples: separate outcome series
        | case                         | window             | price_state | original_target | target_revision | calendar_state | available_measures                                  | unavailable_measures                             |
        | primary_all_available        | primary_1_to_3     | complete    | complete        | raised          | verified       | return_realization_revision                         | none                                             |
        | target_missing_keeps_return  | followup_3_to_12   | complete    | missing         | missing         | verified       | price_return                                        | realization_and_revision                         |
        | lowered_target_is_not_return | primary_1_to_3     | complete    | complete        | lowered         | verified       | return_realization_revision                         | none                                             |
        | price_gap_is_explicit        | primary_1_to_3     | missing     | complete        | unchanged       | verified       | target_revision_only                                | return_and_realization                           |
        | future_day_is_pending        | followup_3_to_12   | not_mature  | complete        | not_due         | verified       | none                                                | pending_not_mature                               |
        | calendar_unknown_blocks      | primary_1_to_3     | complete    | complete        | raised          | unverified     | none                                                | blocked_calendar                                 |

    Scenario: The original target never moves
      Given initial price is P0 and the frozen target is T0
      When market session t closes at Pt and the current target is Tt
      Then price return is Pt divided by P0 minus one
      And original potential realization is Pt minus P0 divided by T0 minus P0
      And target revision change is Tt divided by T0 minus one
      And no ratio is clamped to zero or one
      And Tt never replaces T0 in original potential realization
      And target evidence observed after t never fills U at t

    Scenario: Calendar dates define the continuous windows
      Given reference session opening is O
      When daily outcome dates are generated
      Then every regular session close from O through twelve calendar months is tracked
      And the primary start is the first session on or after O plus one calendar month
      And the primary end is the last session on or before O plus three calendar months
      And follow-up ends on the last session on or before O plus twelve calendar months
      And the committed market calendar version resolves every boundary

  @model @integration_required
  Rule: V5 versus V2 is the only primary model comparison

    Scenario Outline: Classify a comparison request
      Given model pair is <model_pair>
      And sample relation is <sample_relation>
      And trajectory window is <window>
      And outcome metric is <metric>
      And uncertainty protocol is <uncertainty>
      When model comparison is classified
      Then comparison status is <comparison_status>

      Examples: primary and complementary evidence
        | case                         | model_pair | sample_relation       | window           | metric                         | uncertainty | comparison_status                  |
        | primary_v5_v2               | V5_V2      | paired_common         | primary_1_to_3   | price_return                   | predeclared | primary_eligible                   |
        | different_samples_reject    | V5_V2      | unpaired              | primary_1_to_3   | price_return                   | predeclared | unavailable_common_sample_required |
        | weak_coverage_rejects        | V5_V2      | below_minimum         | primary_1_to_3   | price_return                   | predeclared | unavailable_minimum_coverage       |
        | post_hoc_rules_reject        | V5_V2      | paired_common         | primary_1_to_3   | price_return                   | post_hoc    | invalid_confirmatory_claim         |
        | realization_is_secondary     | V5_V2      | paired_common         | primary_1_to_3   | original_potential_realization | predeclared | complementary_only                 |
        | target_revision_is_secondary | V5_V2      | paired_common         | primary_1_to_3   | target_revision_change         | predeclared | complementary_only                 |
        | twelve_month_is_secondary    | V5_V2      | paired_common         | followup_3_to_12 | price_return                   | predeclared | complementary_only                 |
        | other_models_are_secondary   | other_pair | paired_common         | primary_1_to_3   | price_return                   | predeclared | complementary_only                 |

    Scenario: The primary statistic uses the complete daily path
      Given one committed cohort has V5 and V2 scores
      And each eligible session is in the primary window
      When both models share the same eligible instruments
      Then each model receives one cross-sectional Spearman rank correlation
      And the daily paired difference is V5 correlation minus V2 correlation
      And the cohort statistic is the equal-session mean of those paired differences
      And the neutral baseline is zero

    Scenario: Uncertainty rules freeze before outcomes
      Given the primary comparison contract is committed before outcome observation
      Then it fixes minimum common instruments and minimum eligible cohorts
      And it fixes moving-block bootstrap length, repetitions and confidence level
      And each resampled cohort keeps its companies and daily horizons together
      And an interval containing zero yields an inconclusive result
      And missing minimum evidence yields INFERENCE_UNAVAILABLE

    Scenario Outline: Interpret the primary uncertainty interval
      Given comparison status is <comparison_status>
      And the point estimate relation is <point_relation>
      And the confidence interval is <interval_relation>
      When primary superiority is classified
      Then the result is <superiority_result>

      Examples: confirmatory classification
        | case                   | comparison_status  | point_relation | interval_relation | superiority_result |
        | v5_interval_wins       | primary_eligible   | V5_higher      | above_zero        | V5_SUPERIOR        |
        | v2_interval_wins       | primary_eligible   | V2_higher      | below_zero        | V2_SUPERIOR        |
        | zero_remains_plausible | primary_eligible   | V5_higher      | contains_zero     | INCONCLUSIVE       |
        | secondary_cannot_claim | primary_ineligible | V5_higher      | above_zero        | NO_PRIMARY_CLAIM   |

    Scenario: Scores do not become model-owned targets
      Given V1 through V5 output scores only
      When target realization or target revision is reported
      Then the target remains common company evidence
      And no model receives a synthetic target prediction

  Rule: Outcome updates never invent historical scores

    Scenario Outline: Later evidence arrives
      Given scoring cohorts are <cohort_history>
      And future evidence is <future_history>
      When the app updates outcomes
      Then outcomes become <outcome_result>
      And scoring history becomes <score_result>

      Examples: later evidence
        | case                              | cohort_history | future_history | outcome_result          | score_result       |
        | backfill_existing_cohort          | present        | recoverable    | trajectory_extended     | unchanged          |
        | closed_days_create_no_scores      | absent         | recoverable    | evidence_only           | remains_absent     |
        | provider_history_gap_is_explicit  | present        | unavailable    | unavailable_with_reason | unchanged          |
        | later_target_cannot_rewrite_score | present        | revised_target | revision_series_extended| original_preserved |

  Rule: Every published score has a reconciling receipt

    Scenario Outline: Explain a stored score
      Given the score receipt is <receipt_state>
      When the user asks <question_kind>
      Then the explanation is <explanation_result>
      And reconciliation is <reconciliation_result>

      Examples: decomposition and contrast
        | case                         | receipt_state             | question_kind         | explanation_result             | reconciliation_result |
        | explain_final_five           | complete                  | why_score_is_five     | every_calculation_step         | equals_five           |
        | explain_rounding             | complete                  | why_not_eight         | unrounded_and_rounded_steps    | equals_stored_score   |
        | explain_missing_factor       | complete_with_unavailable | which_factors_mattered| includes_unavailable_reasons   | equals_stored_score   |
        | partial_receipt_refuses      | incomplete                | why_score_is_five     | unavailable_with_reason        | not_claimed           |
        | corrupt_receipt_refuses      | corrupt                   | why_score_is_five     | unavailable_with_reason        | not_claimed           |

  Rule: Contrast explanations compare two immutable receipts

    Scenario Outline: Explain a score difference
      Given the first receipt is <first_receipt>
      And the second receipt is <second_receipt>
      When the user compares <comparison_kind>
      Then the difference is classified as <difference_result>

      Examples: score deltas
        | case                         | first_receipt | second_receipt | comparison_kind   | difference_result                    |
        | v5_five_vs_v2_eight         | complete_v5   | complete_v2    | model_comparison  | formula_and_factor_differences       |
        | today_five_vs_yesterday_eight| complete_today| complete_prior | time_comparison   | input_reference_and_context_changes |
        | sector_reference_changed     | complete_today| complete_prior | time_comparison   | reference_change                    |
        | missing_prior_receipt        | complete_today| absent         | time_comparison   | unavailable_with_reason             |
        | changed_formula_version      | complete_new  | complete_old   | version_comparison| formula_change                      |

  Rule: Historical retrieval and replay are different operations

    Scenario Outline: Request a historical score
      Given the cohort is <cohort_state>
      And the replay capsule is <capsule_state>
      And the formula engine is <engine_state>
      When the user requests <operation>
      Then the result is <historical_result>

      Examples: retrieval and replay
        | case                          | cohort_state | capsule_state | engine_state       | operation | historical_result                  |
        | stored_result_is_retrievable | published_on_time | complete   | unavailable        | retrieve  | exact_stored_result                |
        | late_result_is_retrievable   | materialized_late | complete   | unavailable        | retrieve  | exact_stored_result                |
        | complete_capsule_replays     | published_on_time | complete   | available_matching | replay    | verified_same_result               |
        | missing_engine_limits_replay | materialized_late | complete   | unavailable        | replay    | stored_result_only_engine_missing  |
        | replay_mismatch_is_visible   | published_on_time | complete   | available_mismatch | replay    | mismatch_preserving_original       |
        | incomplete_capsule_refuses   | materialized_late | incomplete | available_matching | replay    | unavailable_incomplete_manifest    |
        | corrupt_capsule_refuses      | published_on_time | corrupt    | available_matching | replay    | unavailable_corrupt_capsule        |
        | unpublished_attempt_is_not_score | expired   | partial     | available_matching | retrieve  | unavailable_no_published_score     |

  Rule: Unobserved dates require point-in-time source history

    Scenario Outline: Reconstruct an unobserved date
      Given required source history is <source_history>
      And historical context is <context_history>
      When the user requests a score from an unobserved date
      Then reconstruction is <reconstruction_result>

      Examples: reconstruction limits
        | case                              | source_history             | context_history | reconstruction_result               |
        | complete_history_can_reconstruct  | complete_point_in_time      | complete        | computed_and_marked_reconstructed   |
        | current_target_cannot_fill_past   | current_only_analyst_target | complete        | unavailable_historical_target       |
        | unknown_publication_time_refuses  | publication_time_unknown    | complete        | unavailable_knowledge_time          |
        | missing_historical_universe       | complete_point_in_time      | missing         | unavailable_historical_context      |
        | partial_market_bars_refuse        | incomplete_market_history   | complete        | unavailable_history_gap             |

  Rule: Historical range writes preserve earlier coverage

    Scenario Outline: Merge a provider history response
      Given stored history is <stored_history>
      When the provider returns <incoming_history>
      Then retained history is <retained_history>
      And the write result is <write_result>

      Examples: monotonic coverage
        | case                            | stored_history       | incoming_history            | retained_history          | write_result               |
        | backfill_earlier_fifteen_days  | latest_15_days       | prior_15_days               | complete_30_days          | missing_interval_added     |
        | shorter_refresh_keeps_old_days | complete_30_days     | latest_15_days              | complete_30_days          | overlap_merged             |
        | identical_window_is_noop       | complete_30_days     | identical_latest_15_days    | complete_30_days          | no_change                  |
        | correction_adds_revision       | complete_30_days     | corrected_day               | both_revisions_preserved  | correction_appended        |
        | split_creates_new_basis        | old_basis_30_days    | new_split_basis_15_days     | two_basis_editions        | new_edition_created        |
        | response_gap_stays_visible     | days_1_to_15         | days_20_to_30               | gap_days_16_to_19         | gap_recorded               |

  Rule: Future models cannot use invented historical inputs

    Scenario Outline: Evaluate V6 against older cohorts
      Given V6 requires <input_relation>
      And point-in-time recovery is <recovery_state>
      When the evaluator processes an old V5 cohort
      Then V6 history is <v6_result>
      And comparison status is <comparison_result>

      Examples: future model boundaries
        | case                            | input_relation           | recovery_state | v6_result                     | comparison_result          |
        | old_capsule_contains_all_inputs | subset_of_old_manifest   | not_needed     | replayed_from_old_manifest    | comparable                 |
        | historical_source_can_backfill  | missing_from_old_manifest| complete       | reconstructed_with_marker     | comparable_with_disclosure |
        | current_only_source_cannot_fill | missing_from_old_manifest| current_only   | prospective_only              | not_historically_comparable|
        | missing_historical_context      | subset_of_old_manifest   | context_missing| unavailable                   | not_historically_comparable|
        | corrupt_old_capsule_refuses     | subset_of_old_manifest   | capsule_corrupt| unavailable                   | not_historically_comparable|

  Rule: Development outcomes cannot become holdout evidence

    Scenario Outline: Classify V6 evidence
      Given V6 was frozen <freeze_relation>
      And cohort outcomes were <outcome_exposure>
      When the comparison report classifies the cohort
      Then evidence status is <evidence_result>

      Examples: research and validation samples
        | case                         | freeze_relation       | outcome_exposure       | evidence_result       |
        | prospective_after_freeze     | before_cohort         | unseen_during_design   | prospective_evidence  |
        | untouched_holdout_is_valid   | before_outcome        | held_out               | holdout_evidence      |
        | reused_development_is_sample | after_cohort_analysis | used_during_design     | in_sample_only        |
        | formula_changed_after_result | after_outcome         | observed               | invalid_as_holdout    |

  Rule: Retention cannot remove committed or irreplaceable evidence

    Scenario Outline: Apply storage retention
      Given an input revision is <reference_state>
      And storage pressure is <pressure_state>
      When retention runs
      Then the revision is <retention_result>
      And cohort publication is <publication_result>

      Examples: evidence pinning
        | case                         | reference_state       | pressure_state | retention_result | publication_result          |
        | committed_manifest_pins_input| pinned_by_committed   | normal         | retained         | allowed                     |
        | quota_keeps_pinned_input     | pinned_by_committed   | full           | retained         | new_publication_blocked     |
        | active_attempt_pins_input    | pinned_by_open_attempt| normal         | retained         | allowed                     |
        | irreplaceable_without_cohort | archive_irrecoverable | normal         | retained         | unchanged                   |
        | unknown_recovery_is_protected| archive_unknown       | manual_prune   | retained         | unchanged                   |
        | reconstructible_cache_can_age| cache_reconstructible | normal         | deletion_allowed | unchanged                   |
        | manual_prune_keeps_evidence  | pinned_by_committed   | manual_prune   | retained         | unchanged                   |
