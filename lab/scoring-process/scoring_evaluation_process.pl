% Formal decision model for the durable Android scoring process.
% This file defines laws. It does not implement Android scheduling.
% Public decision predicates require ground inputs and compute one result.

:- module(scoring_evaluation_process, [
    acquisition_transition/3,
    observation_transition/3,
    observation_reconcile/6,
    observation_reconcile/5,
    dispatch_reconcile/4,
    dispatch_reconcile/3,
    claim_decision/3,
    request_effect/4,
    checkpoint_decision/3,
    prospective_input_decision/8,
    late_archive_decision/6,
    manifest_output_decision/4,
    commitment_decision/5,
    materialization_decision/5,
    trajectory_decision/6,
    comparison_decision/6,
    superiority_decision/4,
    durability_decision/5,
    explanation_decision/3,
    replay_decision/5,
    reconstruction_decision/3,
    future_model_decision/3,
    evidence_classification/3,
    history_merge_decision/4,
    retention_decision/3
]).

:- use_module(library(lists)).

% acquisition_transition(CurrentState, Event, Decision).
% State is acquisition(Progress, Control, Selection).
% Control and profile selection are independent dimensions.
% Market time cannot change acquisition state.

acquisition_transition(State, Event, Decision) :-
    (   ground([State, Event])
    ->  acquisition_transition_value(State, Event, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

acquisition_transition_value(State, Event, Decision) :-
    (   \+ acquisition_state(State)
    ->  Decision = rejected(unknown_state)
    ;   \+ acquisition_event(Event)
    ->  Decision = rejected(unknown_event)
    ;   acquisition_event_value(State, Event, Decision)
    ).

acquisition_event_value(_, deadline_reached,
                        rejected(market_time_not_acquisition_event)) :- !.
acquisition_event_value(acquisition(Progress, _, Selection), explicit_pause,
                        acquisition(NextProgress, user_paused, Selection)) :-
    pause_progress(Progress, NextProgress),
    !.
acquisition_event_value(acquisition(Progress, _, Selection), explicit_resume,
                        acquisition(Progress, enabled, Selection)) :- !.
acquisition_event_value(acquisition(Progress, Control, _), profile_unselected,
                        acquisition(NextProgress, Control, unselected)) :-
    pause_progress(Progress, NextProgress),
    !.
acquisition_event_value(acquisition(Progress, Control, _), profile_selected,
                        acquisition(Progress, Control, selected)) :- !.
acquisition_event_value(acquisition(idle, Control, Selection), refresh_demand,
                        acquisition(pending, Control, Selection)) :- !.
acquisition_event_value(State, refresh_demand, State) :- !.
acquisition_event_value(acquisition(pending, enabled, selected), claim,
                        acquisition(running, enabled, selected)) :- !.
acquisition_event_value(_, claim, rejected(not_runnable)) :- !.
acquisition_event_value(acquisition(running, enabled, selected),
                        checkpoint_more_work,
                        acquisition(pending, enabled, selected)) :- !.
acquisition_event_value(acquisition(running, enabled, selected),
                        checkpoint_fulfilled,
                        acquisition(idle, enabled, selected)) :- !.
acquisition_event_value(acquisition(running, enabled, selected), owner_missing,
                         acquisition(interrupted, enabled, selected)) :- !.
acquisition_event_value(acquisition(running, enabled, selected), Event,
                         acquisition(interrupted, enabled, selected)) :-
    memberchk(Event, [invocation_stopped, system_cancelled, lease_expired]),
    !.
acquisition_event_value(acquisition(interrupted, Control, Selection),
                        reconcile_more_work,
                        acquisition(pending, Control, Selection)) :- !.
acquisition_event_value(acquisition(interrupted, Control, Selection),
                        reconcile_fulfilled,
                        acquisition(idle, Control, Selection)) :- !.
acquisition_event_value(_, _, rejected(invalid_transition)).

pause_progress(running, pending) :- !.
pause_progress(Progress, Progress).

acquisition_progress(idle).
acquisition_progress(pending).
acquisition_progress(interrupted).

acquisition_control(enabled).
acquisition_control(user_paused).

profile_selection(selected).
profile_selection(unselected).

acquisition_state(acquisition(running, enabled, selected)).
acquisition_state(acquisition(Progress, Control, Selection)) :-
    acquisition_progress(Progress),
    acquisition_control(Control),
    profile_selection(Selection).

acquisition_event(refresh_demand).
acquisition_event(claim).
acquisition_event(checkpoint_more_work).
acquisition_event(checkpoint_fulfilled).
acquisition_event(owner_missing).
acquisition_event(invocation_stopped).
acquisition_event(system_cancelled).
acquisition_event(lease_expired).
acquisition_event(reconcile_more_work).
acquisition_event(reconcile_fulfilled).
acquisition_event(explicit_pause).
acquisition_event(explicit_resume).
acquisition_event(profile_unselected).
acquisition_event(profile_selected).
acquisition_event(deadline_reached).

% observation_transition(CurrentState, Event, Decision).
% FROZEN is a candidate. COMMITTED fixes the experiment before the cutoff.

observation_transition(State, Event, Decision) :-
    (   ground([State, Event])
    ->  observation_transition_value(State, Event, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

observation_transition_value(State, Event, Decision) :-
    (   \+ observation_state(State)
    ->  Decision = rejected(unknown_state)
    ;   \+ observation_event(Event)
    ->  Decision = rejected(unknown_event)
    ;   terminal_observation_state(State)
    ->  Decision = rejected(terminal_state)
    ;   observation_event_value(State, Event, Decision)
    ).

observation_event_value(open, freeze_manifest, frozen) :- !.
observation_event_value(frozen, commit_experiment, committed) :- !.
observation_event_value(committed, start_computation, computing) :- !.
observation_event_value(computing, outputs_complete, ready) :- !.
observation_event_value(ready, publish_on_time, published_on_time) :- !.
observation_event_value(ready, materialize_late, materialized_late) :- !.
observation_event_value(frozen, canonical_exists, superseded) :- !.
observation_event_value(State, eligibility_failed, not_committed) :-
    precommit_state(State), !.
observation_event_value(State, Event, interrupted(State)) :-
    observation_phase(State),
    memberchk(Event, [owner_missing, invocation_stopped]), !.
observation_event_value(interrupted(Phase), valid_reclaim, Phase) :-
    observation_phase(Phase), !.
observation_event_value(State, deadline_reached, expired) :-
    precommit_state(State), !.
observation_event_value(State, deadline_reached, State) :-
    committed_state(State), !.
observation_event_value(State, context_changed, invalidated) :-
    precommit_state(State), !.
observation_event_value(State, context_changed, State) :-
    committed_state(State), !.
observation_event_value(_, _, rejected(invalid_transition)).

precommit_phase(open).
precommit_phase(frozen).
committed_phase(committed).
committed_phase(computing).
committed_phase(ready).

observation_phase(State) :- precommit_phase(State).
observation_phase(State) :- committed_phase(State).
precommit_state(State) :- precommit_phase(State).
precommit_state(interrupted(Phase)) :- precommit_phase(Phase).
committed_state(State) :- committed_phase(State).
committed_state(interrupted(Phase)) :- committed_phase(Phase).

active_observation_state(State) :- observation_phase(State).
active_observation_state(interrupted(Phase)) :- observation_phase(Phase).

terminal_observation_state(published_on_time).
terminal_observation_state(materialized_late).
terminal_observation_state(expired).
terminal_observation_state(invalidated).
terminal_observation_state(superseded).
terminal_observation_state(not_committed).

observation_state(State) :-
    active_observation_state(State).
observation_state(State) :-
    terminal_observation_state(State).

observation_event(freeze_manifest).
observation_event(commit_experiment).
observation_event(start_computation).
observation_event(outputs_complete).
observation_event(publish_on_time).
observation_event(materialize_late).
observation_event(canonical_exists).
observation_event(eligibility_failed).
observation_event(owner_missing).
observation_event(invocation_stopped).
observation_event(valid_reclaim).
observation_event(deadline_reached).
observation_event(context_changed).

% observation_reconcile(State, Deadline, Context, Owner, Invocation, Decision).
% Context invalidation wins over expiry as the terminal state.
% The reason list still records every detected terminal cause.

observation_reconcile(State, Deadline, Context, Owner, Decision) :-
    observation_reconcile(State, Deadline, Context, Owner, live, Decision).

observation_reconcile(State, Deadline, Context, Owner, Invocation, Decision) :-
    (   ground([State, Deadline, Context, Owner, Invocation])
    ->  observation_reconcile_checked(State, Deadline, Context, Owner,
                                      Invocation, Decision)
    ;   Decision = rejected(non_ground_input)
    ),
    !.

observation_reconcile_checked(State, Deadline, Context, Owner, Invocation,
                              Decision) :-
    (   \+ observation_state(State)
    ->  Decision = rejected(unknown_state)
    ;   \+ deadline_state(Deadline)
    ->  Decision = rejected(unknown_deadline)
    ;   \+ context_state(Context)
    ->  Decision = rejected(unknown_context)
    ;   \+ owner_state(Owner)
    ->  Decision = rejected(unknown_owner)
    ;   \+ invocation_state(Invocation)
    ->  Decision = rejected(unknown_invocation)
    ;   terminal_observation_state(State)
    ->  Decision = terminal_unchanged(State)
    ;   precommit_state(State), Context == changed
    ->  terminal_reasons(Deadline, Context, Reasons),
        Decision = terminated(invalidated, Reasons)
    ;   precommit_state(State), Deadline == reached
    ->  Decision = terminated(expired, [deadline_reached])
    ;   precommit_state(State), Deadline == unknown
    ->  Decision = blocked(time_unknown)
    ;   State = interrupted(Phase)
    ->  Decision = reclaim(Phase)
    ;   Owner \== same
    ->  Decision = interrupt(State)
    ;   memberchk(Invocation, [stopped, lease_expired])
    ->  Decision = interrupt(State)
    ;   Invocation == unknown
    ->  Decision = blocked(invocation_liveness_unknown)
    ;   Decision = attach(State)
    ).

deadline_state(future).
deadline_state(reached).
deadline_state(unknown).

context_state(same).
context_state(changed).

owner_state(same).
owner_state(missing).
owner_state(different).

invocation_state(live).
invocation_state(stopped).
invocation_state(unknown).
invocation_state(lease_expired).

terminal_reasons(Deadline, Context, Reasons) :-
    findall(Reason, terminal_reason(Deadline, Context, Reason), Reasons).

terminal_reason(_, changed, context_changed).
terminal_reason(reached, _, deadline_reached).

% dispatch_reconcile(Dispatch, CurrentClaim, Invocation, Decision).
% Claim identity includes key, process, invocation, generation and revision.

dispatch_reconcile(Dispatch, CurrentClaim, Decision) :-
    dispatch_reconcile(Dispatch, CurrentClaim, live, Decision).

dispatch_reconcile(Dispatch, CurrentClaim, Invocation, Decision) :-
    (   ground([Dispatch, CurrentClaim, Invocation])
    ->  dispatch_reconcile_value(
            Dispatch, CurrentClaim, Invocation, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

dispatch_reconcile_value(Dispatch, CurrentClaim, Invocation, Decision) :-
    (   \+ claim_state(CurrentClaim)
    ->  Decision = rejected(invalid_current_claim)
    ;   \+ invocation_state(Invocation)
    ->  Decision = rejected(unknown_invocation)
    ;   Dispatch == absent
    ->  Decision = no_committed_dispatch
    ;   Dispatch = completed(Item, AttemptSequence, DispatchClaim),
        valid_dispatch(Item, AttemptSequence, DispatchClaim)
    ->  Decision = completed_unchanged(Item, AttemptSequence)
    ;   Dispatch = outstanding(Item, AttemptSequence, DispatchClaim),
        valid_dispatch(Item, AttemptSequence, DispatchClaim),
        DispatchClaim == CurrentClaim,
        memberchk(Invocation, [stopped, lease_expired])
    ->  Decision = mark_interrupted_and_rotate(
            Item, AttemptSequence, invocation_stopped)
    ;   Dispatch = outstanding(Item, AttemptSequence, DispatchClaim),
        valid_dispatch(Item, AttemptSequence, DispatchClaim),
        DispatchClaim == CurrentClaim,
        Invocation == unknown
    ->  Decision = blocked(invocation_liveness_unknown)
    ;   Dispatch = outstanding(Item, AttemptSequence, DispatchClaim),
        valid_dispatch(Item, AttemptSequence, DispatchClaim),
        DispatchClaim == CurrentClaim
    ->  Decision = attach_outstanding(Item, AttemptSequence)
    ;   Dispatch = outstanding(Item, AttemptSequence, DispatchClaim),
        valid_dispatch(Item, AttemptSequence, DispatchClaim)
    ->  Decision = mark_interrupted_and_rotate(
            Item, AttemptSequence, stale_claim)
    ;   Decision = rejected(invalid_dispatch)
    ).

claim_state(no_claim).
claim_state(claim(Key, Process, Invocation, Generation, ClaimedRevision)) :-
    atom(Key),
    atom(Process),
    atom(Invocation),
    integer(Generation),
    Generation >= 0,
    valid_revision(ClaimedRevision).

valid_dispatch(Item, AttemptSequence, DispatchClaim) :-
    atom(Item),
    integer(AttemptSequence),
    AttemptSequence >= 0,
    DispatchClaim = claim(_, _, _, _, _),
    claim_state(DispatchClaim).

claim_decision(Result, Current, Decision) :-
    (   ground([Result, Current])
    ->  claim_decision_value(Result, Current, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

claim_decision_value(Result, Current, Decision) :-
    (   Result == no_claim
    ->  Decision = rejected(invalid_result_claim)
    ;   \+ claim_state(Result)
    ->  Decision = rejected(invalid_result_claim)
    ;   \+ claim_state(Current)
    ->  Decision = rejected(invalid_current_claim)
    ;   Current == no_claim
    ->  Decision = rejected(no_authorized_claim)
    ;   Result = claim(ResultKey, ResultProcess, ResultInvocation,
                       ResultGeneration, ResultRevision),
        Current = claim(CurrentKey, CurrentProcess, CurrentInvocation,
                        CurrentGeneration, CurrentRevision),
        claim_field_decision(
            ResultKey, ResultProcess, ResultInvocation, ResultGeneration,
            ResultRevision, CurrentKey, CurrentProcess, CurrentInvocation,
            CurrentGeneration, CurrentRevision, Decision)
    ).

claim_field_decision(ResultKey, _, _, _, _, CurrentKey, _, _, _, _,
                     rejected(wrong_key)) :-
    ResultKey \== CurrentKey, !.
claim_field_decision(_, ResultProcess, _, _, _, _, CurrentProcess, _, _, _,
                     rejected(stale_process)) :-
    ResultProcess \== CurrentProcess, !.
claim_field_decision(_, _, ResultInvocation, _, _, _, _, CurrentInvocation,
                     _, _, rejected(stale_invocation)) :-
    ResultInvocation \== CurrentInvocation, !.
claim_field_decision(_, _, _, ResultGeneration, _, _, _, _, CurrentGeneration,
                     _, rejected(stale_generation)) :-
    ResultGeneration \== CurrentGeneration, !.
claim_field_decision(_, _, _, _, ResultRevision, _, _, _, _, CurrentRevision,
                     rejected(wrong_claimed_revision)) :-
    ResultRevision \== CurrentRevision, !.
claim_field_decision(_, _, _, _, _, _, _, _, _, _, authorized).

% request_effect(State, RequestKind, RequestedRevision, Effect).
% Observer attachment does not create durable demand.

request_effect(State, RequestKind, RequestedRevision, Effect) :-
    (   ground([State, RequestKind, RequestedRevision])
    ->  request_effect_checked(State, RequestKind, RequestedRevision,
                              Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Effect = Computed.

request_effect_checked(State, RequestKind, RequestedRevision, Effect) :-
    (   \+ acquisition_state(State)
    ->  Effect = rejected(unknown_state)
    ;   \+ valid_revision(RequestedRevision)
    ->  Effect = rejected(invalid_revision)
    ;   RequestKind == observer_attach
    ->  Effect = observer_attached(RequestedRevision)
    ;   RequestKind == refresh_request
    ->  NewRevision is RequestedRevision + 1,
        refresh_effect(State, NewRevision, Effect)
    ;   RequestKind == explicit_resume
    ->  resume_effect(State, RequestedRevision, Effect)
    ;   Effect = rejected(unknown_request)
    ).

request_kind(observer_attach).
request_kind(refresh_request).
request_kind(explicit_resume).

valid_revision(Revision) :-
    integer(Revision),
    Revision >= 0.

refresh_effect(acquisition(_, user_paused, _), Revision,
               remembered_while_paused(Revision)) :- !.
refresh_effect(_, Revision, demand_updated(Revision)).

resume_effect(acquisition(_, user_paused, _), Revision,
              resumed(Revision)) :- !.
resume_effect(_, Revision, ignored_not_paused(Revision)).

% checkpoint_decision(Result, DurableJob, Decision).
% Result also reports whether required work remains.
% DurableJob stores requested, claimed, and fulfilled revisions.

checkpoint_decision(Result, DurableJob, Decision) :-
    (   ground([Result, DurableJob])
    ->  checkpoint_decision_value(Result, DurableJob, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

checkpoint_decision_value(Result, DurableJob, Decision) :-
    (   Result = checkpoint(ResultKey, ResultProcess, ResultInvocation,
                            ResultGeneration, ResultContext,
                            ResultClaimedRevision, WorkStatus),
        DurableJob = job(JobKey, JobProcess, JobInvocation, JobGeneration,
                         JobContext, JobState, RequestedRevision,
                         DurableClaimedRevision, FulfilledRevision)
    ->  checkpoint_fields_decision(
            ResultKey, ResultProcess, ResultInvocation, ResultGeneration,
            ResultContext, ResultClaimedRevision, WorkStatus,
            JobKey, JobProcess, JobInvocation, JobGeneration, JobContext, JobState,
            RequestedRevision, DurableClaimedRevision, FulfilledRevision,
            Decision)
    ;   Decision = rejected(invalid_shape)
    ).

checkpoint_fields_decision(
    ResultKey, ResultProcess, ResultInvocation, ResultGeneration,
    ResultContext, ResultClaimedRevision, WorkStatus,
    JobKey, JobProcess, JobInvocation, JobGeneration, JobContext, JobState,
    RequestedRevision, DurableClaimedRevision, FulfilledRevision,
    Decision
) :-
    (   ResultKey \== JobKey
    ->  Decision = rejected(wrong_key)
    ;   ResultProcess \== JobProcess
    ->  Decision = rejected(stale_process)
    ;   ResultInvocation \== JobInvocation
    ->  Decision = rejected(stale_invocation)
    ;   ResultGeneration \== JobGeneration
    ->  Decision = rejected(stale_generation)
    ;   ResultContext \== JobContext
    ->  Decision = rejected(changed_context)
    ;   JobState \== acquisition(running, enabled, selected)
    ->  Decision = rejected(wrong_state)
    ;   \+ valid_revision(RequestedRevision)
    ->  Decision = rejected(invalid_requested_revision)
    ;   \+ valid_revision(DurableClaimedRevision)
    ->  Decision = rejected(invalid_durable_claimed_revision)
    ;   \+ valid_revision(FulfilledRevision)
    ->  Decision = rejected(invalid_fulfilled_revision)
    ;   \+ valid_revision(ResultClaimedRevision)
    ->  Decision = rejected(invalid_result_claimed_revision)
    ;   FulfilledRevision > DurableClaimedRevision
    ->  Decision = rejected(fulfilled_exceeds_claimed)
    ;   DurableClaimedRevision > RequestedRevision
    ->  Decision = rejected(claimed_exceeds_requested)
    ;   ResultClaimedRevision \== DurableClaimedRevision
    ->  Decision = rejected(wrong_claimed_revision)
    ;   \+ work_status(WorkStatus)
    ->  Decision = rejected(unknown_work_status)
    ;   checkpoint_progress_decision(
            WorkStatus, RequestedRevision, DurableClaimedRevision,
            FulfilledRevision, Decision)
    ).

work_status(remaining).
work_status(complete).

checkpoint_progress_decision(
    remaining, _, _, FulfilledRevision,
    accepted(write_authorized,
             progress(partial),
             fulfillment(unchanged, FulfilledRevision),
             next(pending))) :- !.
checkpoint_progress_decision(
    complete, RequestedRevision, ClaimedRevision, FulfilledRevision,
    accepted(write_authorized,
             progress(complete),
             Fulfillment,
             next(NextState))) :-
    fulfillment_result(FulfilledRevision, ClaimedRevision, Fulfillment),
    (   ClaimedRevision < RequestedRevision
    ->  NextState = pending
    ;   NextState = idle
    ).

fulfillment_result(FulfilledRevision, ClaimedRevision,
                   fulfillment(advanced, ClaimedRevision)) :-
    FulfilledRevision < ClaimedRevision,
    !.
fulfillment_result(FulfilledRevision, _,
                   fulfillment(unchanged, FulfilledRevision)).

% late_archive_decision(DispatchProof, ArchiveAuthority, DeliveryRelation,
%                       ReceiptTime, ManifestState, Decision).
% This path can append immutable evidence after runner fencing.
% It cannot mutate jobs, cache, manifests or scores.

late_archive_decision(DispatchProof, ArchiveAuthority, DeliveryRelation,
                      ReceiptTime, ManifestState, Decision) :-
    (   ground([DispatchProof, ArchiveAuthority, DeliveryRelation,
                ReceiptTime, ManifestState])
    ->  late_archive_decision_value(
            DispatchProof, ArchiveAuthority, DeliveryRelation,
            ReceiptTime, ManifestState, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

late_archive_decision_value(DispatchProof, ArchiveAuthority, DeliveryRelation,
                            ReceiptTime, ManifestState, Decision) :-
    (   \+ dispatch_proof(DispatchProof)
    ->  Decision = rejected(unknown_dispatch_proof)
    ;   \+ archive_authority(ArchiveAuthority)
    ->  Decision = rejected(unknown_archive_authority)
    ;   \+ delivery_relation(DeliveryRelation)
    ->  Decision = rejected(unknown_delivery_relation)
    ;   \+ receipt_time_state(ReceiptTime)
    ->  Decision = rejected(unknown_receipt_time_state)
    ;   \+ manifest_relation(ManifestState)
    ->  Decision = rejected(unknown_manifest_state)
    ;   ArchiveAuthority == erasure_tombstoned
    ->  Decision = rejected(explicit_erasure_prevents_reingest)
    ;   ArchiveAuthority == archive_revoked
    ->  Decision = rejected(archive_authority_revoked)
    ;   DispatchProof == unknown_dispatch
    ->  Decision = rejected(original_dispatch_not_proven)
    ;   DispatchProof == mismatched_dispatch
    ->  Decision = rejected(dispatch_identity_mismatch)
    ;   ReceiptTime == receipt_time_missing
    ->  Decision = rejected(actual_receipt_time_missing)
    ;   DeliveryRelation == incompatible
    ->  late_manifest_effect(ManifestState, ManifestEffect),
        Decision = quarantined(
            incompatible_delivery,
            runner_authority(not_restored),
            time_effect(preserve_actual_receipt_time),
            mutable_effects(none),
            manifest_effect(ManifestEffect))
    ;   late_archive_action(DeliveryRelation, Action),
        late_manifest_effect(ManifestState, ManifestEffect),
        Decision = accepted(
            archive_only(Action),
            runner_authority(not_restored),
            mutable_effects(none),
            time_effect(preserve_actual_receipt_time),
            manifest_effect(ManifestEffect))
    ).

dispatch_proof(original_dispatch).
dispatch_proof(unknown_dispatch).
dispatch_proof(mismatched_dispatch).

archive_authority(archive_granted).
archive_authority(archive_revoked).
archive_authority(erasure_tombstoned).

delivery_relation(compatible_new).
delivery_relation(compatible_duplicate).
delivery_relation(incompatible).

receipt_time_state(actual_receipt_time).
receipt_time_state(receipt_time_missing).

manifest_relation(no_manifest).
manifest_relation(frozen_manifest).
manifest_relation(committed_manifest).

late_archive_action(compatible_new, append_revision).
late_archive_action(compatible_duplicate, idempotent_noop).

late_manifest_effect(no_manifest, not_linked_to_manifest).
late_manifest_effect(frozen_manifest, outside_frozen_manifest).
late_manifest_effect(committed_manifest, outside_frozen_manifest).

% prospective_input_decision separates local availability, source publication,
% source age and historical reconstruction evidence.

prospective_input_decision(ObservedTime, PublicationTime, AvailabilityPolicy,
                           SourceAge, SourceContract, Schema, Membership,
                           Decision) :-
    (   ground([ObservedTime, PublicationTime, AvailabilityPolicy, SourceAge,
                SourceContract, Schema, Membership])
    ->  prospective_input_decision_value(
            ObservedTime, PublicationTime, AvailabilityPolicy, SourceAge,
            SourceContract, Schema, Membership, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

prospective_input_decision_value(
    ObservedTime, PublicationTime, AvailabilityPolicy, SourceAge,
    SourceContract, Schema, Membership, Decision
) :-
    (   \+ observed_relation(ObservedTime)
    ->  Decision = rejected(unknown_observed_relation)
    ;   \+ publication_relation(PublicationTime)
    ->  Decision = rejected(unknown_publication_relation)
    ;   \+ availability_policy(AvailabilityPolicy)
    ->  Decision = rejected(unknown_availability_policy)
    ;   \+ source_age_state(SourceAge)
    ->  Decision = rejected(unknown_source_age)
    ;   \+ source_contract_state(SourceContract)
    ->  Decision = rejected(unknown_source_contract)
    ;   \+ schema_state(Schema)
    ->  Decision = rejected(unknown_schema)
    ;   \+ membership_state(Membership)
    ->  Decision = rejected(unknown_membership)
    ;   findall(Reason,
                prospective_input_reason(
                    ObservedTime, PublicationTime, AvailabilityPolicy,
                    SourceAge, SourceContract, Schema, Membership, Reason),
                Reasons),
        prospective_input_result(PublicationTime, Reasons, Decision)
    ).

publication_relation(publication_before_cut).
publication_relation(publication_at_cut).
publication_relation(publication_after_cut).
publication_relation(publication_unknown).

availability_policy(local_observation_sufficient).
availability_policy(source_publication_required).

source_age_state(age_accepted).
source_age_state(age_expired).
source_age_state(age_unknown).

prospective_input_reason(after_cut, _, _, _, _, _, _, observed_after_cut).
prospective_input_reason(unknown, _, _, _, _, _, _, observed_time_unknown).
prospective_input_reason(_, publication_after_cut, _, _, _, _, _,
                         source_publication_after_cut).
prospective_input_reason(_, publication_unknown,
                         source_publication_required, _, _, _, _,
                         source_publication_time_unknown).
prospective_input_reason(_, _, _, age_expired, _, _, _, source_age_expired).
prospective_input_reason(_, _, _, age_unknown, _, _, _, source_age_unknown).
prospective_input_reason(_, _, _, _, incompatible, _, _, source_incompatible).
prospective_input_reason(_, _, _, _, _, incompatible, _, schema_incompatible).
prospective_input_reason(_, _, _, _, _, _, not_member, outside_universe).

prospective_input_result(publication_unknown, [],
                         eligible(publication_time_unknown)) :- !.
prospective_input_result(_, [], eligible(publication_time_known)) :- !.
prospective_input_result(_, Reasons, unavailable(Reasons)).

observed_relation(before_cut).
observed_relation(at_cut).
observed_relation(after_cut).
observed_relation(unknown).

source_contract_state(compatible).
source_contract_state(incompatible).

schema_state(compatible).
schema_state(incompatible).

membership_state(member).
membership_state(not_member).

% manifest_output_decision(ManifestId, Outputs, Context, Decision).
% Outputs is a list of output(Model, ManifestId) terms.

manifest_output_decision(ManifestId, Outputs, Context, Decision) :-
    (   ground([ManifestId, Outputs, Context])
    ->  manifest_output_value(ManifestId, Outputs, Context, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

manifest_output_value(ManifestId, Outputs, Context, Decision) :-
    (   Context == changed
    ->  Decision = invalidate_attempt
    ;   Context \== same
    ->  Decision = rejected(unknown_context)
    ;   \+ valid_output_shape(Outputs)
    ->  Decision = discard_outputs_and_recompute
    ;   output_uses_other_manifest(Outputs, ManifestId)
    ->  Decision = discard_outputs_and_recompute
    ;   output_models(Outputs, Models),
        required_models(Required),
        subtract(Required, Models, Missing),
        missing_output_decision(Missing, Decision)
    ).

required_models([v1, v2, v3, v4, v5]).

valid_output_shape(Outputs) :-
    is_list(Outputs),
    maplist(valid_output_entry, Outputs),
    output_models_with_duplicates(Outputs, ModelsWithDuplicates),
    sort(ModelsWithDuplicates, Models),
    same_length(ModelsWithDuplicates, Models).

valid_output_entry(output(Model, ManifestId)) :-
    required_model(Model),
    ground(ManifestId).

required_model(Model) :-
    required_models(Models),
    memberchk(Model, Models).

output_models_with_duplicates(Outputs, Models) :-
    findall(Model, member(output(Model, _), Outputs), Models).

output_models(Outputs, Models) :-
    output_models_with_duplicates(Outputs, ModelsWithDuplicates),
    sort(ModelsWithDuplicates, Models).

output_uses_other_manifest(Outputs, ManifestId) :-
    member(output(_, OutputManifest), Outputs),
    OutputManifest \== ManifestId,
    !.

missing_output_decision([], ready_to_materialize) :- !.
missing_output_decision(Missing, compute_missing(Missing)).

% commitment_decision(State, CutRelation, Capsule, Reservation, Decision).
% The canonical key is reserved with the complete capsule before the cutoff.

commitment_decision(State, CutRelation, Capsule, Reservation, Decision) :-
    (   ground([State, CutRelation, Capsule, Reservation])
    ->  commitment_decision_value(
            State, CutRelation, Capsule, Reservation, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

commitment_decision_value(State, CutRelation, Capsule, Reservation, Decision) :-
    (   \+ observation_state(State)
    ->  Decision = rejected(unknown_state)
    ;   \+ commitment_cut_relation(CutRelation)
    ->  Decision = rejected(unknown_cut_relation)
    ;   \+ commitment_capsule(Capsule)
    ->  Decision = rejected(unknown_capsule)
    ;   \+ commitment_reservation(Reservation)
    ->  Decision = rejected(unknown_reservation)
    ;   Reservation == same_commitment,
        has_committed_experiment(State)
    ->  Decision = already_committed(read_original_commitment)
    ;   Reservation == same_commitment
    ->  Decision = rejected(inconsistent_commitment_state)
    ;   State \== frozen
    ->  Decision = rejected(wrong_state)
    ;   Capsule \== complete_capsule
    ->  Decision = rejected(experiment_not_fully_frozen)
    ;   CutRelation == cut_unknown
    ->  Decision = blocked(time_unknown)
    ;   memberchk(CutRelation, [at_cut, after_cut])
    ->  Decision = rejected(commitment_too_late)
    ;   Reservation == different_commitment
    ->  Decision = superseded(existing_commitment_wins)
    ;   Decision = accepted(commit_and_reserve)
    ).

commitment_cut_relation(before_cut).
commitment_cut_relation(at_cut).
commitment_cut_relation(after_cut).
commitment_cut_relation(cut_unknown).

commitment_capsule(complete_capsule).
commitment_capsule(incomplete_capsule).
commitment_capsule(corrupt_capsule).

commitment_reservation(absent).
commitment_reservation(same_commitment).
commitment_reservation(different_commitment).

has_committed_experiment(State) :-
    committed_state(State).
has_committed_experiment(published_on_time).
has_committed_experiment(materialized_late).

% materialization_decision(Commitment, Completion, Outputs, Existing, Decision).

materialization_decision(Commitment, Completion, Outputs, Existing, Decision) :-
    (   ground([Commitment, Completion, Outputs, Existing])
    ->  materialization_decision_value(
            Commitment, Completion, Outputs, Existing, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

materialization_decision_value(
    Commitment, Completion, Outputs, Existing, Decision
) :-
    (   \+ commitment_proof(Commitment)
    ->  Decision = rejected(unknown_commitment_proof)
    ;   \+ materialization_time(Completion)
    ->  Decision = rejected(unknown_materialization_time)
    ;   \+ materialization_outputs(Outputs)
    ->  Decision = rejected(unknown_output_state)
    ;   \+ materialization_existing(Existing)
    ->  Decision = rejected(unknown_existing_result)
    ;   Commitment \== committed_before_cut
    ->  Decision = rejected(no_valid_pre_cut_commitment)
    ;   Outputs \== complete_same_manifest
    ->  Decision = rejected(incomplete_or_mixed_outputs)
    ;   Existing == same_result
    ->  Decision = already_materialized(original_classification_preserved)
    ;   Existing == different_result
    ->  Decision = rejected(immutable_result_conflict)
    ;   Completion == time_unknown
    ->  Decision = blocked(time_unknown)
    ;   Completion == before_commit
    ->  Decision = rejected(materialization_before_commitment)
    ;   Completion == before_cut
    ->  Decision = accepted(published_on_time)
    ;   Decision = accepted(materialized_late)
    ).

commitment_proof(committed_before_cut).
commitment_proof(absent).
commitment_proof(at_or_after_cut).
commitment_proof(corrupt_commitment).

materialization_time(before_commit).
materialization_time(before_cut).
materialization_time(at_cut).
materialization_time(after_cut).
materialization_time(time_unknown).

materialization_outputs(complete_same_manifest).
materialization_outputs(incomplete_outputs).
materialization_outputs(mixed_manifests).
materialization_outputs(wrong_engine).

materialization_existing(absent).
materialization_existing(same_result).
materialization_existing(different_result).

% explanation_decision(ReceiptState, Question, Decision).

explanation_decision(ReceiptState, Question, Decision) :-
    (   ground([ReceiptState, Question])
    ->  explanation_decision_value(ReceiptState, Question, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

explanation_decision_value(ReceiptState, Question, Decision) :-
    (   \+ receipt_state(ReceiptState)
    ->  Decision = rejected(unknown_receipt_state)
    ;   Question == decompose
    ->  decomposition_decision(ReceiptState, Decision)
    ;   Question = contrast(OtherReceipt),
        receipt_state(OtherReceipt)
    ->  contrast_decision(ReceiptState, OtherReceipt, Decision)
    ;   Decision = rejected(unknown_question)
    ).

receipt_state(complete).
receipt_state(complete_with_unavailable).
receipt_state(incomplete).
receipt_state(absent).
receipt_state(corrupt).

decomposition_decision(complete, exact_reconciling_explanation) :- !.
decomposition_decision(complete_with_unavailable,
                       exact_reconciling_explanation) :- !.
decomposition_decision(incomplete, unavailable(incomplete_receipt)) :- !.
decomposition_decision(absent, unavailable(receipt_absent)) :- !.
decomposition_decision(corrupt, unavailable(receipt_corrupt)).

contrast_decision(complete, complete, exact_contrast) :- !.
contrast_decision(complete, complete_with_unavailable, exact_contrast) :- !.
contrast_decision(complete_with_unavailable, complete, exact_contrast) :- !.
contrast_decision(complete_with_unavailable, complete_with_unavailable,
                  exact_contrast) :- !.
contrast_decision(incomplete, _, unavailable(incomplete_receipt)) :- !.
contrast_decision(absent, _, unavailable(receipt_absent)) :- !.
contrast_decision(corrupt, _, unavailable(receipt_corrupt)) :- !.
contrast_decision(_, incomplete, unavailable(comparator_incomplete)) :- !.
contrast_decision(_, absent, unavailable(comparator_absent)) :- !.
contrast_decision(_, corrupt, unavailable(comparator_corrupt)).

% replay_decision(Cohort, Capsule, Engine, Operation, Decision).

replay_decision(Cohort, Capsule, Engine, Operation, Decision) :-
    (   ground([Cohort, Capsule, Engine, Operation])
    ->  replay_decision_value(Cohort, Capsule, Engine, Operation, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

replay_decision_value(Cohort, Capsule, Engine, Operation, Decision) :-
    (   \+ cohort_state(Cohort)
    ->  Decision = rejected(unknown_cohort_state)
    ;   \+ capsule_state(Capsule)
    ->  Decision = rejected(unknown_capsule_state)
    ;   \+ replay_engine_state(Engine)
    ->  Decision = rejected(unknown_engine_state)
    ;   \+ replay_operation(Operation)
    ->  Decision = rejected(unknown_operation)
    ;   \+ published_cohort_state(Cohort)
    ->  Decision = unavailable(no_published_score)
    ;   Capsule == corrupt
    ->  Decision = unavailable(corrupt_capsule)
    ;   Operation == retrieve
    ->  Decision = exact_stored_result
    ;   Capsule \== complete
    ->  Decision = unavailable(incomplete_manifest)
    ;   Engine == unavailable
    ->  Decision = stored_result_only(engine_unavailable)
    ;   Engine == available_mismatch
    ->  Decision = replay_mismatch(original_preserved)
    ;   Decision = replay_verified(same_result)
    ).

cohort_state(published_on_time).
cohort_state(materialized_late).
cohort_state(expired).
cohort_state(invalidated).
cohort_state(absent).

published_cohort_state(published_on_time).
published_cohort_state(materialized_late).

capsule_state(complete).
capsule_state(incomplete).
capsule_state(partial).
capsule_state(corrupt).

replay_engine_state(available_matching).
replay_engine_state(available_mismatch).
replay_engine_state(unavailable).

replay_operation(retrieve).
replay_operation(replay).

% reconstruction_decision(SourceHistory, HistoricalContext, Decision).

reconstruction_decision(SourceHistory, HistoricalContext, Decision) :-
    (   ground([SourceHistory, HistoricalContext])
    ->  reconstruction_value(SourceHistory, HistoricalContext, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

reconstruction_value(SourceHistory, HistoricalContext, Decision) :-
    (   \+ source_history_state(SourceHistory)
    ->  Decision = rejected(unknown_source_history)
    ;   \+ historical_context_state(HistoricalContext)
    ->  Decision = rejected(unknown_historical_context)
    ;   HistoricalContext == missing
    ->  Decision = unavailable(historical_context_missing)
    ;   SourceHistory == complete_point_in_time
    ->  Decision = reconstructed(marked_reconstructed)
    ;   SourceHistory == current_only
    ->  Decision = unavailable(historical_source_missing)
    ;   SourceHistory == publication_time_unknown
    ->  Decision = unavailable(knowledge_time_unknown)
    ;   Decision = unavailable(history_gap)
    ).

source_history_state(complete_point_in_time).
source_history_state(current_only).
source_history_state(publication_time_unknown).
source_history_state(incomplete).

historical_context_state(complete).
historical_context_state(missing).

% future_model_decision(InputRelation, RecoveryState, Decision).

future_model_decision(InputRelation, RecoveryState, Decision) :-
    (   ground([InputRelation, RecoveryState])
    ->  future_model_value(InputRelation, RecoveryState, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

future_model_value(InputRelation, RecoveryState, Decision) :-
    (   \+ model_input_relation(InputRelation)
    ->  Decision = rejected(unknown_input_relation)
    ;   \+ recovery_state(RecoveryState)
    ->  Decision = rejected(unknown_recovery_state)
    ;   memberchk(RecoveryState, [context_missing, capsule_corrupt])
    ->  Decision = unavailable(not_historically_comparable)
    ;   InputRelation == subset_of_old_manifest
    ->  Decision = replay_old_manifest(comparable)
    ;   RecoveryState == complete
    ->  Decision = reconstructed_with_marker(comparable_with_disclosure)
    ;   Decision = prospective_only(not_historically_comparable)
    ).

model_input_relation(subset_of_old_manifest).
model_input_relation(missing_from_old_manifest).

recovery_state(not_needed).
recovery_state(complete).
recovery_state(current_only).
recovery_state(context_missing).
recovery_state(capsule_corrupt).

% evidence_classification(FreezeRelation, OutcomeExposure, Decision).

evidence_classification(FreezeRelation, OutcomeExposure, Decision) :-
    (   ground([FreezeRelation, OutcomeExposure])
    ->  evidence_classification_value(FreezeRelation, OutcomeExposure,
                                     Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

evidence_classification_value(FreezeRelation, OutcomeExposure, Decision) :-
    (   \+ freeze_relation(FreezeRelation)
    ->  Decision = rejected(unknown_freeze_relation)
    ;   \+ outcome_exposure(OutcomeExposure)
    ->  Decision = rejected(unknown_outcome_exposure)
    ;   OutcomeExposure == used_during_design
    ->  Decision = in_sample_only
    ;   FreezeRelation == after_outcome
    ->  Decision = invalid_as_holdout
    ;   FreezeRelation == before_cohort,
        OutcomeExposure == unseen_during_design
    ->  Decision = prospective_evidence
    ;   FreezeRelation == before_outcome,
        OutcomeExposure == held_out
    ->  Decision = holdout_evidence
    ;   Decision = insufficient_holdout_provenance
    ).

freeze_relation(before_cohort).
freeze_relation(before_outcome).
freeze_relation(after_cohort_analysis).
freeze_relation(after_outcome).

outcome_exposure(unseen_during_design).
outcome_exposure(held_out).
outcome_exposure(used_during_design).
outcome_exposure(observed).

% history_merge_decision(StoredCoverage, IncomingRelation, Basis, Decision).

history_merge_decision(StoredCoverage, IncomingRelation, Basis, Decision) :-
    (   ground([StoredCoverage, IncomingRelation, Basis])
    ->  history_merge_value(StoredCoverage, IncomingRelation, Basis,
                           Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

history_merge_value(StoredCoverage, IncomingRelation, Basis, Decision) :-
    (   \+ stored_coverage_state(StoredCoverage)
    ->  Decision = rejected(unknown_stored_coverage)
    ;   \+ incoming_history_relation(IncomingRelation)
    ->  Decision = rejected(unknown_incoming_relation)
    ;   \+ basis_relation(Basis)
    ->  Decision = rejected(unknown_basis_relation)
    ;   StoredCoverage == none,
        Basis == changed
    ->  Decision = rejected(basis_change_without_prior)
    ;   StoredCoverage == none,
        memberchk(IncomingRelation, [identical, correction])
    ->  Decision = rejected(relation_requires_prior_history)
    ;   Basis == changed
    ->  Decision = create_new_basis_edition(preserve_prior)
    ;   IncomingRelation == identical
    ->  Decision = no_change(preserve_existing)
    ;   IncomingRelation == correction
    ->  Decision = append_revision(preserve_prior)
    ;   IncomingRelation == gapped
    ->  Decision = merge_and_record_gap(preserve_existing)
    ;   Decision = merge_coverage(preserve_existing)
    ).

stored_coverage_state(none).
stored_coverage_state(partial).
stored_coverage_state(complete).

incoming_history_relation(earlier_missing_interval).
incoming_history_relation(overlap).
incoming_history_relation(identical).
incoming_history_relation(correction).
incoming_history_relation(gapped).

basis_relation(same).
basis_relation(changed).

% retention_decision(ReferenceState, Pressure, Decision).

retention_decision(ReferenceState, Pressure, Decision) :-
    (   ground([ReferenceState, Pressure])
    ->  retention_decision_value(ReferenceState, Pressure, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

retention_decision_value(ReferenceState, Pressure, Decision) :-
    (   \+ reference_state(ReferenceState)
    ->  Decision = rejected(unknown_reference_state)
    ;   \+ pressure_state(Pressure)
    ->  Decision = rejected(unknown_pressure_state)
    ;   protected_reference(ReferenceState),
        Pressure == full
    ->  Decision = retain_and_block_new_publication
    ;   protected_reference(ReferenceState)
    ->  Decision = retain
    ;   ReferenceState == cache_reconstructible
    ->  Decision = deletion_allowed
    ;   ReferenceState == unreferenced_expired
    ->  Decision = rejected(recoverability_required)
    ;   Pressure == full
    ->  Decision = retain_and_block_new_publication
    ;   Decision = retain
    ).

reference_state(pinned_by_committed).
reference_state(pinned_by_open_attempt).
reference_state(archive_irrecoverable).
reference_state(archive_recovery_unknown).
reference_state(cache_reconstructible).
reference_state(unreferenced_active).
reference_state(unreferenced_expired).

protected_reference(pinned_by_committed).
protected_reference(pinned_by_open_attempt).
protected_reference(archive_irrecoverable).
protected_reference(archive_recovery_unknown).

pressure_state(normal).
pressure_state(full).
pressure_state(manual_prune).

% trajectory_decision classifies one daily market-session observation.
% It keeps price return, original-target realization and target revisions separate.

trajectory_decision(Window, Price, OriginalTarget, TargetRevision, Calendar,
                    Decision) :-
    (   ground([Window, Price, OriginalTarget, TargetRevision, Calendar])
    ->  trajectory_decision_value(
            Window, Price, OriginalTarget, TargetRevision, Calendar, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

trajectory_decision_value(
    Window, Price, OriginalTarget, TargetRevision, Calendar, Decision
) :-
    (   \+ trajectory_window(Window)
    ->  Decision = rejected(unknown_trajectory_window)
    ;   \+ trajectory_price_state(Price)
    ->  Decision = rejected(unknown_price_state)
    ;   \+ original_target_state(OriginalTarget)
    ->  Decision = rejected(unknown_original_target_state)
    ;   \+ target_revision_state(TargetRevision)
    ->  Decision = rejected(unknown_target_revision_state)
    ;   \+ trajectory_calendar_state(Calendar)
    ->  Decision = rejected(unknown_calendar_state)
    ;   Calendar == calendar_unverified
    ->  Decision = blocked(calendar_unverified)
    ;   Window == after_followup
    ->  Decision = outside_declared_followup
    ;   Price == price_not_mature
    ->  Decision = pending(not_mature)
    ;   trajectory_metrics(
            Price, OriginalTarget, TargetRevision, Metrics, Unavailable),
        trajectory_result(Metrics, Unavailable, Decision)
    ).

trajectory_window(pre_primary).
trajectory_window(primary_window).
trajectory_window(extended_followup).
trajectory_window(after_followup).

trajectory_price_state(price_complete).
trajectory_price_state(price_not_mature).
trajectory_price_state(price_missing).
trajectory_price_state(price_basis_invalid).

original_target_state(original_target_complete).
original_target_state(original_target_missing).
original_target_state(original_target_zero_potential).
original_target_state(original_target_invalid).

target_revision_state(target_revision_raised).
target_revision_state(target_revision_lowered).
target_revision_state(target_revision_unchanged).
target_revision_state(target_revision_missing).
target_revision_state(target_revision_not_due).

trajectory_calendar_state(calendar_verified).
trajectory_calendar_state(calendar_unverified).

trajectory_metrics(Price, OriginalTarget, TargetRevision, Metrics, Unavailable) :-
    findall(Metric,
            trajectory_metric(Price, OriginalTarget, TargetRevision, Metric),
            Metrics),
    findall(Missing,
            trajectory_unavailable(
                Price, OriginalTarget, TargetRevision, Missing),
            RawUnavailable),
    sort(RawUnavailable, Unavailable).

trajectory_metric(price_complete, _, _, price_return).
trajectory_metric(price_complete, original_target_complete, _,
                  original_potential_realization).
trajectory_metric(_, _, TargetRevision, target_revision_change) :-
    memberchk(TargetRevision,
              [target_revision_raised, target_revision_lowered,
               target_revision_unchanged]).

trajectory_unavailable(price_missing, _, _, price_return).
trajectory_unavailable(price_basis_invalid, _, _, price_return).
trajectory_unavailable(price_missing, original_target_complete, _,
                       original_potential_realization).
trajectory_unavailable(price_basis_invalid, original_target_complete, _,
                       original_potential_realization).
trajectory_unavailable(_, original_target_missing, _,
                       original_potential_realization).
trajectory_unavailable(_, original_target_zero_potential, _,
                       original_potential_realization).
trajectory_unavailable(_, original_target_invalid, _,
                       original_potential_realization).
trajectory_unavailable(_, _, target_revision_missing, target_revision_change).
trajectory_unavailable(_, _, target_revision_not_due, target_revision_change).

trajectory_result([], Unavailable, unavailable(Unavailable)) :- !.
trajectory_result(Metrics, [], eligible(Metrics)) :- !.
trajectory_result(Metrics, Unavailable,
                  eligible(Metrics, unavailable(Unavailable))).

% comparison_decision defines the confirmatory V5-V2 comparison boundary.

comparison_decision(ModelPair, Sample, Window, Metric, Uncertainty, Decision) :-
    (   ground([ModelPair, Sample, Window, Metric, Uncertainty])
    ->  comparison_decision_value(
            ModelPair, Sample, Window, Metric, Uncertainty, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

comparison_decision_value(ModelPair, Sample, Window, Metric, Uncertainty,
                          Decision) :-
    (   \+ comparison_model_pair(ModelPair)
    ->  Decision = rejected(unknown_model_pair)
    ;   \+ comparison_sample(Sample)
    ->  Decision = rejected(unknown_sample)
    ;   \+ trajectory_window(Window)
    ->  Decision = rejected(unknown_trajectory_window)
    ;   \+ comparison_metric(Metric)
    ->  Decision = rejected(unknown_comparison_metric)
    ;   \+ uncertainty_state(Uncertainty)
    ->  Decision = rejected(unknown_uncertainty_state)
    ;   Uncertainty == uncertainty_post_hoc
    ->  Decision = invalid_for_confirmatory_claim
    ;   Sample == unpaired_sample
    ->  Decision = unavailable(common_sample_required)
    ;   Sample == coverage_below_minimum
    ->  Decision = unavailable(minimum_coverage_not_met)
    ;   Uncertainty == uncertainty_insufficient
    ->  Decision = descriptive_only(inference_unavailable)
    ;   ModelPair == v5_v2,
        Window == primary_window,
        Metric == price_return
    ->  Decision = primary_comparison_eligible
    ;   Decision = complementary_metric_only
    ).

comparison_model_pair(v5_v2).
comparison_model_pair(other_model_pair).

comparison_sample(paired_common_sample).
comparison_sample(unpaired_sample).
comparison_sample(coverage_below_minimum).

comparison_metric(price_return).
comparison_metric(original_potential_realization).
comparison_metric(target_revision_change).

uncertainty_state(uncertainty_predeclared).
uncertainty_state(uncertainty_insufficient).
uncertainty_state(uncertainty_post_hoc).

% superiority_decision interprets only the predeclared primary comparison.
% A confidence interval must exclude zero before either model can win.

superiority_decision(Eligibility, Point, Interval, Decision) :-
    (   ground([Eligibility, Point, Interval])
    ->  superiority_decision_value(Eligibility, Point, Interval, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

superiority_decision_value(Eligibility, Point, Interval, Decision) :-
    (   \+ primary_eligibility(Eligibility)
    ->  Decision = rejected(unknown_primary_eligibility)
    ;   \+ point_estimate_relation(Point)
    ->  Decision = rejected(unknown_point_estimate_relation)
    ;   \+ confidence_interval_relation(Interval)
    ->  Decision = rejected(unknown_confidence_interval_relation)
    ;   Eligibility == primary_ineligible
    ->  Decision = no_primary_claim
    ;   Interval == interval_unavailable
    ->  Decision = inconclusive(uncertainty_unavailable)
    ;   Interval == interval_contains_zero
    ->  Decision = inconclusive(uncertainty_includes_no_difference)
    ;   Interval == interval_above_zero,
        Point == v5_higher
    ->  Decision = v5_superior
    ;   Interval == interval_below_zero,
        Point == v2_higher
    ->  Decision = v2_superior
    ;   Decision = rejected(inconsistent_point_and_interval)
    ).

primary_eligibility(primary_eligible).
primary_eligibility(primary_ineligible).

point_estimate_relation(v5_higher).
point_estimate_relation(tied).
point_estimate_relation(v2_higher).

confidence_interval_relation(interval_above_zero).
confidence_interval_relation(interval_contains_zero).
confidence_interval_relation(interval_below_zero).
confidence_interval_relation(interval_unavailable).

% durability_decision separates evidence requirements from mechanisms and cost.

durability_decision(Evidence, Mechanism, Driver, Measurement, Decision) :-
    (   ground([Evidence, Mechanism, Driver, Measurement])
    ->  durability_decision_value(
            Evidence, Mechanism, Driver, Measurement, Computed)
    ;   Computed = rejected(non_ground_input)
    ),
    Decision = Computed.

durability_decision_value(Evidence, Mechanism, Driver, Measurement, Decision) :-
    (   \+ evidence_durability_class(Evidence)
    ->  Decision = rejected(unknown_evidence_class)
    ;   \+ durability_mechanism(Mechanism)
    ->  Decision = rejected(unknown_durability_mechanism)
    ;   \+ driver_verification(Driver)
    ->  Decision = rejected(unknown_driver_verification)
    ;   \+ durability_measurement(Measurement)
    ->  Decision = rejected(unknown_measurement)
    ;   Driver == driver_unverified
    ->  Decision = blocked(driver_not_verified)
    ;   Measurement == cost_not_measured
    ->  Decision = blocked(measurement_required)
    ;   Measurement == cost_measured_unacceptable
    ->  Decision = blocked(performance_requirement_not_met)
    ;   Evidence == irreplaceable_evidence,
        memberchk(Mechanism, [wal_normal, alternative_unproven])
    ->  Decision = blocked(durability_requirement_not_met)
    ;   Decision = requirement_met
    ).

evidence_durability_class(irreplaceable_evidence).
evidence_durability_class(reconstructible_cache).

durability_mechanism(wal_full).
durability_mechanism(wal_normal).
durability_mechanism(alternative_proven).
durability_mechanism(alternative_unproven).

driver_verification(driver_verified).
driver_verification(driver_unverified).

durability_measurement(cost_measured_acceptable).
durability_measurement(cost_measured_unacceptable).
durability_measurement(cost_not_measured).

:- begin_tests(scoring_evaluation_process).

single_decision(Closure) :-
    findall(Decision, call(Closure, Decision), Decisions),
    assertion(Decisions = [_]),
    maplist(ground, Decisions).

test(acquisition_has_no_time_expiry) :-
    acquisition_transition(
        acquisition(running, enabled, selected),
        deadline_reached,
        rejected(market_time_not_acquisition_event)
    ).

test(profile_switch_preserves_user_pause) :-
    Start = acquisition(pending, user_paused, selected),
    acquisition_transition(Start, profile_unselected, Unselected),
    assertion(Unselected == acquisition(pending, user_paused, unselected)),
    acquisition_transition(Unselected, profile_selected, Reselected),
    assertion(Reselected == acquisition(pending, user_paused, selected)),
    acquisition_transition(Reselected, claim, rejected(not_runnable)),
    acquisition_transition(Reselected, explicit_resume, Resumed),
    acquisition_transition(Resumed, claim,
                           acquisition(running, enabled, selected)).

test(observation_expires_at_deadline) :-
    observation_transition(frozen, deadline_reached, expired).

test(interrupted_phase_is_preserved) :-
    observation_transition(computing, owner_missing,
                           interrupted(computing)),
    observation_transition(interrupted(computing), valid_reclaim,
                           computing).

test(acquisition_transitions_are_single) :-
    forall(
        (acquisition_state(State), acquisition_event(Event)),
        (findall(Next,
                 acquisition_transition(State, Event, Next),
                 NextStates),
         assertion(NextStates = [_]))
    ).

test(observation_transitions_are_single) :-
    forall(
        (observation_state(State), observation_event(Event)),
        (findall(Next,
                 observation_transition(State, Event, Next),
                 NextStates),
         assertion(NextStates = [_]))
    ).

test(two_month_gap_separates_records) :-
    acquisition_transition(
        acquisition(running, enabled, selected),
        owner_missing,
        acquisition(interrupted, enabled, selected)
    ),
    observation_reconcile(frozen, reached, same, missing,
                          terminated(expired, [deadline_reached])).

test(context_and_deadline_reasons_are_preserved) :-
    observation_reconcile(frozen, reached, changed, missing,
                          terminated(invalidated,
                                     [context_changed, deadline_reached])).

test(observation_reconciliation_is_total_and_single) :-
    forall(
        (observation_state(State), deadline_state(Deadline),
         context_state(Context), owner_state(Owner)),
        (findall(Decision,
                 observation_reconcile(State, Deadline, Context, Owner,
                                       Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(death_after_dispatch_rotates_item) :-
    OldClaim = claim(scoring, process_a, invocation_a, 7, 7),
    dispatch_reconcile(outstanding(aapl_price, 42, OldClaim), no_claim,
                       mark_interrupted_and_rotate(
                           aapl_price, 42, stale_claim)).

test(live_owner_keeps_outstanding_dispatch) :-
    Claim = claim(scoring, process_a, invocation_a, 7, 7),
    dispatch_reconcile(outstanding(aapl_price, 42, Claim), Claim,
                       attach_outstanding(aapl_price, 42)).

test(new_generation_fences_same_process_dispatch) :-
    OldClaim = claim(scoring, process_a, invocation_a, 7, 7),
    NewClaim = claim(scoring, process_a, invocation_b, 8, 8),
    dispatch_reconcile(outstanding(aapl_price, 42, OldClaim), NewClaim,
                       mark_interrupted_and_rotate(
                           aapl_price, 42, stale_claim)).

test(no_dispatch_cannot_claim_fairness_progress) :-
    dispatch_reconcile(absent, no_claim, no_committed_dispatch).

test(explicit_pause_does_not_expire) :-
    State = acquisition(pending, user_paused, selected),
    request_effect(State, explicit_resume, 7, resumed(7)),
    findall(Effect,
            request_effect(State, explicit_resume, 7, Effect),
            Effects),
    assertion(Effects == [resumed(7)]).

test(activity_recreation_only_attaches) :-
    request_effect(acquisition(running, enabled, selected),
                   observer_attach, 7, observer_attached(7)).

test(new_request_increments_revision) :-
    request_effect(acquisition(running, enabled, selected),
                   refresh_request, 7, demand_updated(8)).

test(paused_request_is_remembered) :-
    request_effect(acquisition(pending, user_paused, selected),
                   refresh_request, 7,
                   remembered_while_paused(8)).

test(paused_resume_cannot_prove_ignored, [fail]) :-
    request_effect(acquisition(pending, user_paused, selected),
                   explicit_resume, 7, ignored_not_paused(7)).

test(paused_refresh_cannot_prove_normal_update, [fail]) :-
    request_effect(acquisition(pending, user_paused, selected),
                   refresh_request, 7, demand_updated(8)).

test(request_decisions_are_total_and_single) :-
    forall(
        (acquisition_state(State), request_kind(Request)),
        (findall(Effect, request_effect(State, Request, 0, Effect), Effects),
         assertion(Effects = [_]))
    ).

test(partial_checkpoint_cannot_fulfill) :-
    checkpoint_decision(
        checkpoint(scoring, process_a, invocation_a, 7, context_a, 7, remaining),
        job(scoring, process_a, invocation_a, 7, context_a,
            acquisition(running, enabled, selected), 7, 7, 6),
        accepted(write_authorized,
                 progress(partial),
                 fulfillment(unchanged, 6),
                 next(pending))
    ).

test(complete_checkpoint_can_fulfill) :-
    checkpoint_decision(
        checkpoint(scoring, process_a, invocation_a, 7, context_a, 7, complete),
        job(scoring, process_a, invocation_a, 7, context_a,
            acquisition(running, enabled, selected), 7, 7, 6),
        accepted(write_authorized,
                 progress(complete),
                 fulfillment(advanced, 7),
                 next(idle))
    ).

test(complete_checkpoint_preserves_newer_request) :-
    checkpoint_decision(
        checkpoint(scoring, process_a, invocation_a, 7, context_a, 7, complete),
        job(scoring, process_a, invocation_a, 7, context_a,
            acquisition(running, enabled, selected), 8, 7, 6),
        accepted(write_authorized,
                 progress(complete),
                 fulfillment(advanced, 7),
                 next(pending))
    ).

test(checkpoint_must_match_durable_claim) :-
    checkpoint_decision(
        checkpoint(scoring, process_a, invocation_a, 7, context_a, 6, complete),
        job(scoring, process_a, invocation_a, 7, context_a,
            acquisition(running, enabled, selected), 8, 7, 6),
        rejected(wrong_claimed_revision)
    ).

test(checkpoint_rejects_stale_owner) :-
    checkpoint_decision(
        checkpoint(scoring, process_old, invocation_old, 7, context_a, 7, complete),
        job(scoring, process_new, invocation_new, 8, context_a,
            acquisition(running, enabled, selected), 8, 7, 6),
        rejected(stale_process)
    ).

test(checkpoint_rejects_unknown_generation_without_binding) :-
    checkpoint_decision(
        checkpoint(scoring, process_a, invocation_a, Generation, context_a, 7, complete),
        job(scoring, process_a, invocation_a, 7, context_a,
            acquisition(running, enabled, selected), 7, 7, 6),
        Decision
    ),
    assertion(var(Generation)),
    assertion(Decision == rejected(non_ground_input)).

test(partial_outputs_resume_same_manifest) :-
    manifest_output_decision(
        manifest_7,
        [output(v1, manifest_7), output(v2, manifest_7)],
        same,
        compute_missing([v3, v4, v5])
    ).

test(mixed_manifest_outputs_restart) :-
    manifest_output_decision(
        manifest_7,
        [output(v1, manifest_7), output(v2, manifest_8)],
        same,
        discard_outputs_and_recompute
    ).

test(all_models_share_manifest) :-
    manifest_output_decision(
        manifest_7,
        [output(v1, manifest_7), output(v2, manifest_7),
         output(v3, manifest_7), output(v4, manifest_7),
         output(v5, manifest_7)],
        same,
        ready_to_materialize
    ).

test(corrupt_output_element_forces_recompute) :-
    manifest_output_decision(
        manifest_7,
        [output(v1, manifest_7), output(v2, manifest_7),
         output(v3, manifest_7), output(v4, manifest_7),
         output(v5, manifest_7), corrupt],
        same,
        discard_outputs_and_recompute
    ).

test(valid_manifest_cannot_prove_wrong_decision, [fail]) :-
    manifest_output_decision(
        manifest_7,
        [output(v1, manifest_7), output(v2, manifest_7),
         output(v3, manifest_7), output(v4, manifest_7),
         output(v5, manifest_7)],
        same,
        rejected(unknown_context)
    ).

test(valid_checkpoint_cannot_prove_invalid_shape, [fail]) :-
    checkpoint_decision(
        checkpoint(scoring, process_a, invocation_a, 7, context_a, 7, complete),
        job(scoring, process_a, invocation_a, 7, context_a,
            acquisition(running, enabled, selected), 7, 7, 6),
        rejected(invalid_shape)
    ).

test(complete_receipt_explains_exact_score) :-
    explanation_decision(complete, decompose,
                         exact_reconciling_explanation).

test(missing_comparator_refuses_contrast) :-
    explanation_decision(complete, contrast(absent),
                         unavailable(comparator_absent)).

test(explanation_decisions_are_single) :-
    forall(
        (receipt_state(Receipt),
         (Question = decompose ;
          (receipt_state(Other), Question = contrast(Other)))),
        (findall(Decision,
                 explanation_decision(Receipt, Question, Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(replay_verifies_same_result) :-
    replay_decision(published_on_time, complete, available_matching, replay,
                    replay_verified(same_result)).

test(retrieval_survives_missing_engine) :-
    replay_decision(materialized_late, complete, unavailable, retrieve,
                    exact_stored_result).

test(replay_mismatch_preserves_original) :-
    replay_decision(published_on_time, complete, available_mismatch, replay,
                    replay_mismatch(original_preserved)).

test(replay_decisions_are_total_and_single) :-
    forall(
        (cohort_state(Cohort), capsule_state(Capsule),
         replay_engine_state(Engine), replay_operation(Operation)),
        (findall(Decision,
                 replay_decision(Cohort, Capsule, Engine, Operation,
                                 Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(complete_point_in_time_history_can_reconstruct) :-
    reconstruction_decision(complete_point_in_time, complete,
                            reconstructed(marked_reconstructed)).

test(current_value_cannot_reconstruct_past) :-
    reconstruction_decision(current_only, complete,
                            unavailable(historical_source_missing)).

test(reconstruction_decisions_are_total_and_single) :-
    forall(
        (source_history_state(SourceHistory),
         historical_context_state(HistoricalContext)),
        (findall(Decision,
                 reconstruction_decision(SourceHistory, HistoricalContext,
                                         Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(future_model_replays_when_manifest_is_complete) :-
    future_model_decision(subset_of_old_manifest, not_needed,
                          replay_old_manifest(comparable)).

test(future_model_stays_prospective_for_current_only_data) :-
    future_model_decision(missing_from_old_manifest, current_only,
                          prospective_only(not_historically_comparable)).

test(future_model_decisions_are_total_and_single) :-
    forall(
        (model_input_relation(InputRelation),
         recovery_state(RecoveryState)),
        (findall(Decision,
                 future_model_decision(InputRelation, RecoveryState,
                                       Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(prospective_model_evidence_is_valid) :-
    evidence_classification(before_cohort, unseen_during_design,
                            prospective_evidence).

test(development_sample_is_not_holdout) :-
    evidence_classification(after_cohort_analysis, used_during_design,
                            in_sample_only).

test(evidence_classifications_are_total_and_single) :-
    forall(
        (freeze_relation(FreezeRelation),
         outcome_exposure(OutcomeExposure)),
        (findall(Decision,
                 evidence_classification(FreezeRelation, OutcomeExposure,
                                         Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(shorter_window_preserves_older_coverage) :-
    history_merge_decision(complete, overlap, same,
                           merge_coverage(preserve_existing)).

test(correction_preserves_prior_revision) :-
    history_merge_decision(complete, correction, same,
                           append_revision(preserve_prior)).

test(basis_change_preserves_prior_edition) :-
    history_merge_decision(complete, overlap, changed,
                           create_new_basis_edition(preserve_prior)).

test(history_merge_decisions_are_total_and_single) :-
    forall(
        (stored_coverage_state(Stored),
         incoming_history_relation(Incoming), basis_relation(Basis)),
        (findall(Decision,
                 history_merge_decision(Stored, Incoming, Basis, Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(committed_evidence_survives_manual_prune) :-
    retention_decision(pinned_by_committed, manual_prune, retain).

test(full_storage_blocks_without_deleting_evidence) :-
    retention_decision(pinned_by_committed, full,
                        retain_and_block_new_publication).

test(irreplaceable_archive_survives_without_cohort) :-
    retention_decision(archive_irrecoverable, manual_prune, retain).

test(unknown_recovery_archive_survives_storage_pressure) :-
    retention_decision(archive_recovery_unknown, full,
                       retain_and_block_new_publication).

test(retention_decisions_are_total_and_single) :-
    forall(
        (reference_state(Reference), pressure_state(Pressure)),
        (findall(Decision,
                 retention_decision(Reference, Pressure, Decision),
                 Decisions),
         assertion(Decisions = [_]))
    ).

test(public_decisions_reject_non_ground_inputs) :-
    observation_reconcile(open, future, same, _,
                          rejected(non_ground_input)),
    dispatch_reconcile(no_dispatch, no_claim, _,
                       rejected(non_ground_input)),
    claim_decision(_, no_claim, rejected(non_ground_input)),
    prospective_input_decision(
        before_cut, publication_unknown, local_observation_sufficient,
        age_accepted, compatible, compatible, _,
        rejected(non_ground_input)),
    late_archive_decision(
        original_dispatch, archive_granted, compatible_new,
        actual_receipt_time, _, rejected(non_ground_input)),
    manifest_output_decision(_, [], same, rejected(non_ground_input)),
    commitment_decision(
        frozen, before_cut, complete_capsule, _,
        rejected(non_ground_input)),
    materialization_decision(
        committed_before_cut, after_cut, complete_same_manifest, _,
        rejected(non_ground_input)),
    trajectory_decision(
        primary_window, price_complete, original_target_complete,
        target_revision_unchanged, _, rejected(non_ground_input)),
    comparison_decision(
        v5_v2, paired_common_sample, primary_window, price_return, _,
        rejected(non_ground_input)),
    superiority_decision(
        primary_eligible, v5_higher, _, rejected(non_ground_input)),
    durability_decision(
        irreplaceable_evidence, wal_full, driver_verified, _,
        rejected(non_ground_input)),
    explanation_decision(_, decompose, rejected(non_ground_input)),
    replay_decision(_, complete, available_matching, replay,
                    rejected(non_ground_input)),
    reconstruction_decision(_, complete, rejected(non_ground_input)),
    future_model_decision(_, not_needed, rejected(non_ground_input)),
    evidence_classification(_, held_out, rejected(non_ground_input)),
    history_merge_decision(_, overlap, same,
                           rejected(non_ground_input)),
    retention_decision(_, normal, rejected(non_ground_input)).

% Regressions for the reconciled contract. These tests precede implementation.

test(late_response_archives_without_reviving_runner) :-
    late_archive_decision(
        original_dispatch,
        archive_granted,
        compatible_new,
        actual_receipt_time,
        frozen_manifest,
        accepted(archive_only(append_revision),
                 runner_authority(not_restored),
                 mutable_effects(none),
                 time_effect(preserve_actual_receipt_time),
                 manifest_effect(outside_frozen_manifest))
    ).

test(late_duplicate_is_idempotent) :-
    late_archive_decision(
        original_dispatch,
        archive_granted,
        compatible_duplicate,
        actual_receipt_time,
        committed_manifest,
        accepted(archive_only(idempotent_noop),
                 runner_authority(not_restored),
                 mutable_effects(none),
                 time_effect(preserve_actual_receipt_time),
                 manifest_effect(outside_frozen_manifest))
    ).

test(explicit_erasure_prevents_late_reingest) :-
    late_archive_decision(
        original_dispatch,
        erasure_tombstoned,
        compatible_new,
        actual_receipt_time,
        no_manifest,
        rejected(explicit_erasure_prevents_reingest)
    ).

test(incompatible_late_delivery_is_quarantined) :-
    late_archive_decision(
        original_dispatch,
        archive_granted,
        incompatible,
        actual_receipt_time,
        no_manifest,
        quarantined(incompatible_delivery,
                    runner_authority(not_restored),
                    time_effect(preserve_actual_receipt_time),
                    mutable_effects(none),
                    manifest_effect(not_linked_to_manifest))
    ).

test(incompatible_late_delivery_preserves_no_runner_authority) :-
    late_archive_decision(
        original_dispatch,
        archive_granted,
        incompatible,
        actual_receipt_time,
        frozen_manifest,
        quarantined(incompatible_delivery,
                    runner_authority(not_restored),
                    time_effect(preserve_actual_receipt_time),
                    mutable_effects(none),
                    manifest_effect(outside_frozen_manifest))
    ).

test(local_observation_can_establish_prospective_availability) :-
    prospective_input_decision(
        before_cut,
        publication_unknown,
        local_observation_sufficient,
        age_accepted,
        compatible,
        compatible,
        member,
        eligible(publication_time_unknown)
    ).

test(publication_unknown_blocks_historical_policy) :-
    prospective_input_decision(
        before_cut,
        publication_unknown,
        source_publication_required,
        age_accepted,
        compatible,
        compatible,
        member,
        unavailable([source_publication_time_unknown])
    ).

test(local_observation_never_rejuvenates_source_age) :-
    prospective_input_decision(
        before_cut,
        publication_unknown,
        local_observation_sufficient,
        age_expired,
        compatible,
        compatible,
        member,
        unavailable([source_age_expired])
    ).

test(primary_daily_trajectory_keeps_outcomes_separate) :-
    trajectory_decision(
        primary_window,
        price_complete,
        original_target_complete,
        target_revision_raised,
        calendar_verified,
        eligible([price_return,
                  original_potential_realization,
                  target_revision_change]))
    .

test(target_missing_does_not_remove_price_return) :-
    trajectory_decision(
        extended_followup,
        price_complete,
        original_target_missing,
        target_revision_missing,
        calendar_verified,
        eligible([price_return],
                 unavailable([original_potential_realization,
                              target_revision_change]))
    ).

test(price_gap_marks_return_and_realization_unavailable) :-
    trajectory_decision(
        primary_window,
        price_missing,
        original_target_complete,
        target_revision_unchanged,
        calendar_verified,
        eligible([target_revision_change],
                 unavailable([original_potential_realization,
                              price_return]))
    ).

test(v5_v2_primary_requires_common_sample_and_predeclared_uncertainty) :-
    comparison_decision(
        v5_v2,
        paired_common_sample,
        primary_window,
        price_return,
        uncertainty_predeclared,
        primary_comparison_eligible
    ).

test(post_hoc_uncertainty_cannot_support_primary_claim) :-
    comparison_decision(
        v5_v2,
        paired_common_sample,
        primary_window,
        price_return,
        uncertainty_post_hoc,
        invalid_for_confirmatory_claim
    ).

test(primary_interval_above_zero_supports_v5_superiority) :-
    superiority_decision(
        primary_eligible,
        v5_higher,
        interval_above_zero,
        v5_superior
    ).

test(primary_interval_containing_zero_is_inconclusive) :-
    superiority_decision(
        primary_eligible,
        v5_higher,
        interval_contains_zero,
        inconclusive(uncertainty_includes_no_difference)
    ).

test(primary_interval_below_zero_supports_v2_superiority) :-
    superiority_decision(
        primary_eligible,
        v2_higher,
        interval_below_zero,
        v2_superior
    ).

test(non_primary_result_cannot_support_superiority) :-
    superiority_decision(
        primary_ineligible,
        v5_higher,
        interval_above_zero,
        no_primary_claim
    ).

test(full_is_reference_when_verified_and_measured) :-
    durability_decision(
        irreplaceable_evidence,
        wal_full,
        driver_verified,
        cost_measured_acceptable,
        requirement_met
    ).

test(normal_cannot_satisfy_irreplaceable_durability_by_convenience) :-
    durability_decision(
        irreplaceable_evidence,
        wal_normal,
        driver_verified,
        cost_measured_acceptable,
        blocked(durability_requirement_not_met)
    ).

test(same_process_stopped_invocation_is_not_live) :-
    Claim = claim(scoring, process_a, invocation_a, 7, 7),
    dispatch_reconcile(
        outstanding(aapl_price, 42, Claim),
        Claim,
        stopped,
        mark_interrupted_and_rotate(aapl_price, 42, invocation_stopped)
    ).

test(system_cancellation_preserves_user_control) :-
    acquisition_transition(
        acquisition(running, enabled, selected),
        system_cancelled,
        acquisition(interrupted, enabled, selected)
    ).

test(frozen_candidate_must_commit_before_computation) :-
    observation_transition(frozen, commit_experiment, committed),
    observation_transition(committed, start_computation, computing).

test(complete_candidate_reserves_key_before_cut) :-
    commitment_decision(
        frozen,
        before_cut,
        complete_capsule,
        absent,
        accepted(commit_and_reserve)
    ).

test(candidate_cannot_commit_at_cut) :-
    commitment_decision(
        frozen,
        at_cut,
        complete_capsule,
        absent,
        rejected(commitment_too_late)
    ).

test(expired_candidate_cannot_claim_existing_commitment) :-
    commitment_decision(
        expired,
        after_cut,
        complete_capsule,
        same_commitment,
        rejected(inconsistent_commitment_state)
    ).

test(deadline_does_not_expire_committed_experiment) :-
    observation_transition(committed, deadline_reached, committed).

test(committed_experiment_can_materialize_late) :-
    materialization_decision(
        committed_before_cut,
        after_cut,
        complete_same_manifest,
        absent,
        accepted(materialized_late)
    ).

test(late_archive_domain_is_total_and_single) :-
    forall(
        (dispatch_proof(Dispatch), archive_authority(Authority),
         delivery_relation(Delivery), receipt_time_state(ReceiptTime),
         manifest_relation(Manifest)),
        single_decision(late_archive_decision(
            Dispatch, Authority, Delivery, ReceiptTime, Manifest))
    ).

test(prospective_input_domain_is_total_and_single) :-
    forall(
        (observed_relation(Observed), publication_relation(Publication),
         availability_policy(Policy), source_age_state(Age),
         source_contract_state(Source), schema_state(Schema),
         membership_state(Membership)),
        single_decision(prospective_input_decision(
            Observed, Publication, Policy, Age, Source, Schema, Membership))
    ).

test(commitment_domain_is_total_and_single) :-
    forall(
        (observation_state(State), commitment_cut_relation(Cut),
         commitment_capsule(Capsule), commitment_reservation(Reservation)),
        single_decision(commitment_decision(
            State, Cut, Capsule, Reservation))
    ).

test(materialization_domain_is_total_and_single) :-
    forall(
        (commitment_proof(Commitment), materialization_time(Time),
         materialization_outputs(Outputs), materialization_existing(Existing)),
        single_decision(materialization_decision(
            Commitment, Time, Outputs, Existing))
    ).

test(trajectory_domain_is_total_and_single) :-
    forall(
        (trajectory_window(Window), trajectory_price_state(Price),
         original_target_state(Target), target_revision_state(Revision),
         trajectory_calendar_state(Calendar)),
        single_decision(trajectory_decision(
            Window, Price, Target, Revision, Calendar))
    ).

test(comparison_domain_is_total_and_single) :-
    forall(
        (comparison_model_pair(Pair), comparison_sample(Sample),
         trajectory_window(Window), comparison_metric(Metric),
         uncertainty_state(Uncertainty)),
        single_decision(comparison_decision(
            Pair, Sample, Window, Metric, Uncertainty))
    ).

test(superiority_domain_is_total_and_single) :-
    forall(
        (primary_eligibility(Eligibility), point_estimate_relation(Point),
         confidence_interval_relation(Interval)),
        single_decision(superiority_decision(
            Eligibility, Point, Interval))
    ).

test(durability_domain_is_total_and_single) :-
    forall(
        (evidence_durability_class(Evidence),
         durability_mechanism(Mechanism), driver_verification(Driver),
         durability_measurement(Measurement)),
        single_decision(durability_decision(
            Evidence, Mechanism, Driver, Measurement))
    ).

test(claim_identity_is_total_and_single_for_samples) :-
    Claims = [
        no_claim,
        claim(job_a, process_a, invocation_a, 0, 0),
        claim(job_a, process_a, invocation_b, 1, 0),
        claim(job_b, process_b, invocation_a, 1, 1)
    ],
    forall(
        (member(Result, Claims), member(Current, Claims)),
        single_decision(claim_decision(Result, Current))
    ).

:- end_tests(scoring_evaluation_process).
