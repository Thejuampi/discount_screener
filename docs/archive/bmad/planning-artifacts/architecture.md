---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\product-brief-valuation-change-visibility.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\product-brief-valuation-change-visibility-distillate.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\ux-design-specification.md"
  - "G:\\dev\\repos\\discount_screener\\apps\\android\\README.md"
status: "complete"
workflowType: "architecture"
project_name: "Valuation Change Visibility"
user_name: "Juan"
date: "2026-04-23"
---

# Architecture Decision Document

## Project Context

Valuation Change Visibility is an Android-first capability for Discount Screener that must help a returning analyst understand **what changed, by how much, since when, why rank moved, and whether the signal is trustworthy**. The architecture must support three user-facing layers consistently:

1. **List surfaces (opportunities default, tracked peer surface)** — triage
2. **Detail screen** — explanation
3. **History** — trend confidence over time

The architecture must keep reusable business rules portable so this capability can later be shared with desktop and cross-platform contracts.

## Architectural Goals

- make **materiality**, **provenance**, **baselines**, and **attribution** deterministic
- keep comparison and interpretation rules out of Compose UI
- preserve Android’s existing **functional core / imperative shell** boundary
- support warm restore, partial refresh, and sparse-data states without producing misleading explanations
- make the resulting rules testable at unit, repository, and UI levels

## Constraints and Existing Foundations

- Android app already follows a layered structure: `core/` for pure Kotlin engine/model logic, `app/` for Android data/domain/presentation/ui layers
- SQLite persistence and warm restore already exist
- revision history already exists and can replay persisted historical detail
- tracked rows already expose rank movement and valuation change metadata in the Android app layer
- current feature state is Android-first; desktop parity is deliberately out of scope for this planning slice

## Core Architectural Decisions

### Decision Priority Analysis

**Critical decisions**

- comparison anchors must be deterministic
- provenance state model must be shared across list/detail/history
- rank movement attribution must avoid false certainty
- materiality thresholds must be centralized
- persisted revision history must reconstruct historical truth, not current state

**Important decisions**

- determine which logic belongs in `core/` vs `app/`
- define read models for tracked rows and history summaries
- define degraded-state behavior for no baseline, stale-only, missing analyst coverage, and partial refresh

**Deferred decisions**

- user-configurable thresholds
- notifications and alert routing
- desktop parity and shared-contract harmonization

### Domain Architecture

The feature should be modeled as a **valuation change intelligence pipeline**:

1. **Raw inputs**
   - market snapshot
   - weighted analyst target snapshot
   - refresh event metadata
   - persisted revision history
   - ranked candidate ordering

2. **Derived domain outputs**
   - materiality classification
   - provenance state
   - change baseline
   - rank delta
   - change attribution
   - trend summary

3. **UI projections**
   - tracked row badges/chips
   - detail summary cards
   - history timeline and significant revision lists

### Platform Boundary Decision

**Decision:** keep durable state handling and refresh orchestration in `app/`, but move the pure interpretation rules into `core/` over time.

**Rationale:**  
The rules for determining what counts as significant, how rank movement is represented, and how trend summaries are derived are not Android-specific. They are product-domain rules and should live in the functional core so Android and desktop can eventually share them. Persistence, SQLite bootstrap, timestamps, and live refresh orchestration remain in `app/`.

## Data Architecture

### Canonical Value Objects

The architecture should standardize these value objects:

- `ValuationBaseline`
  - symbol
  - anchor type (`restored_session`, `live_session`, `historical_revision`)
  - target fair value
  - market price
  - rank index
  - captured timestamp

- `ValuationChangeAssessment`
  - direction
  - change percent / bps
  - tier (`none`, `significant`, `major`)
  - previous value
  - current value

- `RankMovementAssessment`
  - previous rank
  - current rank
  - places moved
  - direction

- `ProvenanceState`
  - `Restored`
  - `LiveUpdating`
  - `LiveRefreshed`
  - `StaleUnavailable`
  - optional freshness timestamp

- `ChangeAttribution`
  - `PriceMoved`
  - `TargetChanged`
  - `RelativeReRank`
  - `CombinedMove`

- `TrendSummary`
  - direction (`up`, `down`, `mixed`, `flat`, `insufficient_data`)
  - summary text
  - evidence metrics (first/last values, average step, significant-event count)

### Persistence Decisions

**Decision:** continue to persist symbol revisions and latest symbol state in SQLite, but treat the persisted rows as **historical facts** and never rebuild past revisions from current in-memory detail.

**Required stored fields**

- symbol
- evaluated timestamp
- previous/current target values
- market price snapshot
- rank context if available
- refresh source / provenance marker
- enough payload to reconstruct historical detail truthfully

**Rationale:**  
History is only trustworthy if each revision can be reconstructed independently from its persisted payload.

### Baseline Semantics

**Decision:** use explicit baseline types rather than implicit “previous value” assumptions.

- list baseline on reopen: most recent stored session baseline
- list baseline in-session: latest live update baseline
- history baseline: immediately previous revision in the selected window

**Rationale:**  
This removes ambiguity from “since last seen” and makes tests deterministic.

## API and Communication Patterns

This feature is internal to the app boundary. The relevant communication pattern is not remote API design but **in-process state projection**.

**Decision:** project all valuation change intelligence through a repository snapshot contract rather than letting UI derive it ad hoc.

Snapshot outputs should already include:

- tracked rows and opportunity rows with change metadata, provenance, and separate ranking contexts
- selected detail with provenance and before/now context
- selected history with revision-level truth

## Frontend / Presentation Architecture

### State Management

**Decision:** maintain a single presentation snapshot (`DashboardSnapshot` -> `DashboardUiState`) as the source of truth for UI rendering, but keep interpretation rules upstream.

**Presentation responsibilities**

- decide what to show on each screen
- route between list/detail/history
- preserve selection and comparison context

**Presentation must not**

- invent materiality rules
- infer attribution logic
- guess freshness or baseline semantics

### Component Architecture

Use a summary-first composition:

- list row components consume precomputed badges/chips
- detail summary consumes precomputed explanation fields
- history components consume revision events and trend summaries

Compose components should remain dumb renderers of already-interpreted state.

## Implementation Patterns

### Core Pattern: Interpret Then Render

1. ingest raw/persisted state
2. compute deterministic domain assessments
3. project to read models
4. render in UI

### Attribution Pattern

If one driver clearly dominates, use a single label. If multiple drivers materially changed or confidence is low, emit `CombinedMove` instead of overfitting.

### Degraded State Pattern

All empty and degraded states must be explicit domain states, not null-driven UI improvisation:

- no prior baseline
- restored but awaiting live update
- stale/unavailable
- no analyst target history
- partial refresh / mixed freshness

### Validation Pattern

Every summary surface must be backed by visible evidence:

- summary sentence
- before/now comparison
- timestamp
- revision or table evidence

## Project Structure and Ownership

### Recommended Ownership

**`apps/android/core`**

- value objects for change assessment, attribution, provenance, trend summaries
- pure functions for:
  - threshold classification
  - trend derivation
  - attribution selection
  - summary sentence scaffolding

**`apps/android/app/data`**

- SQLite persistence
- warm restore and revision loading
- refresh orchestration
- baseline capture at restore/live boundaries

**`apps/android/app/domain`**

- repository contracts
- read-model types used by presentation
- use cases for snapshot acquisition and symbol selection

**`apps/android/app/presentation`**

- route state and tab/subview state
- bind repository snapshots to UI state

**`apps/android/app/ui`**

- tracked list badges/chips
- detail summary and before/now cards
- history trend/timeline/table presentation

## Cross-Cutting Concerns

### Performance

- reuse persisted latest state for warm restore
- compute pure change assessments cheaply and incrementally
- keep history summarization bounded by selected time window

### Security and Privacy

- no new external security boundary introduced by this feature
- continue existing local SQLite handling
- never imply provenance certainty the system cannot support

### Reliability

- partial refresh must degrade gracefully rather than emit misleading change stories
- missing analyst data must suppress attribution claims that depend on it

## Validation Results

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**  
The design keeps one consistent flow from persisted/live inputs to interpreted change signals to UI projections. The comparison-anchor, provenance, and attribution decisions reinforce each other rather than competing.

**Pattern Consistency:**  
Functional-core rules in `core/` align with the repo’s Android architecture and support eventual desktop reuse. Summary-first UI patterns align with the UX specification.

**Structure Alignment:**  
The proposed ownership model respects the existing separation between `core/` and `app/`, and isolates Android-specific persistence/orchestration from platform-agnostic valuation-change logic.

### Requirements Coverage Validation ✅

**Feature Coverage:**  
The architecture supports list triage, detail explanation, and history confidence flows, including provenance, materiality, attribution, and degraded states.

**Functional Requirements Coverage:**  
All core requirement statements from the product brief are covered by explicit data objects, decision rules, or projection responsibilities.

**Non-Functional Requirements Coverage:**  
Trust, portability, determinism, and testability are directly addressed through explicit domain objects, baseline semantics, and validation patterns.

### Implementation Readiness Validation ✅

**Decision Completeness:**  
The core semantics that could otherwise drift between screens are defined explicitly: thresholds, provenance states, baseline anchors, and attribution outputs.

**Structure Completeness:**  
Ownership boundaries are clear enough for AI agents and human contributors to place new logic consistently.

**Pattern Completeness:**  
Conflict-prone areas—partial refresh, combined drivers, stale data, and no baseline—have clear guidance.

### Gap Analysis Results

**Critical gaps:** none blocking for specification work.  
**Important gaps:** future PRD/epics should define whether rank history itself is exposed visually in v1 or deferred to later slices.  
**Nice-to-have gaps:** later parity work for desktop/shared contracts and configurable thresholds.

### Architecture Readiness Assessment

**Overall Status:** READY FOR PRD / STORY BREAKDOWN  
**Confidence Level:** High

**Key strengths**

- deterministic semantics for change interpretation
- strong alignment between UX promises and architecture boundaries
- portable domain design for later desktop reuse
- explicit handling of degraded trust states

**Areas for future enhancement**

- shared contracts for valuation-change assessments
- rank-history event lane if later needed
- notification/eventing model once alerts enter scope

### Implementation Handoff

**AI agent guidelines**

- keep pure interpretation rules in `core/` whenever possible
- do not let Compose components invent meaning from raw values
- preserve explicit provenance and baseline semantics in every new surface
- prefer `CombinedMove` over false precision when confidence is mixed

**First implementation priority**

Move any remaining Android-app-only materiality, attribution, and trend logic toward shared pure helpers so future list/detail/history changes cannot drift.
