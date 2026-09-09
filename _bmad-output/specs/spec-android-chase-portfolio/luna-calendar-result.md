# Calendar worker result

## Scope

This worker fixes review findings R2 and R3 for Android calendar and Positions date selection.

The worker preserves the nullable calendar map API and the shared calendar owner.

## Changes

- The ViewModel no longer treats a calendar map key as proof of fresh evidence.
- Stale positive and negative entries can reach the calendar owner.
- Recent empty answers still avoid a provider request through the owner policy.
- Calendar cancellation now propagates instead of becoming a negative answer.
- A new gate or profile cancels an older calendar hydration job.
- Positions chooses the nearest eligible log date for each ticker.
- Settled history cannot hide an eligible future date.
- Calendar dates remain the second source after eligible log dates.
- Score row dates remain the final fallback.
- All date conversion uses the New York session day.

## Proposed document reconciliation

The PRD and spec should state one source order for Positions dates.

1. Use an eligible earnings-log date for the ticker.
2. Use the shared Yahoo calendar cache.
3. Use the scored row date.
4. Show no closeness tag when all dates are missing or past.

The PRD and spec should state that the calendar owner controls freshness.

They should state that stale dates and old empty answers can request new evidence.

They should state that fresh future dates and recent empty answers do not repeat Yahoo requests.

They should state that calendar requests do not add feed symbols.

The review record should link this result and the final green test evidence.

## Gherkin cases

### Calendar freshness

```gherkin
Scenario Outline: The calendar owner controls cache freshness
  Given a portfolio lot has a <cache state> calendar entry
  When Positions asks for the lot report date
  Then the owner <provider action>
  And Positions <date result>

Examples:
  | Case | cache state       | provider action       | date result          |
  | R2-1 | expired date      | requests fresh data  | uses fresh date      |
  | R2-2 | expired empty     | requests fresh data  | uses fresh date      |
  | R2-3 | recent empty      | skips the provider   | shows no date        |
  | R2-4 | fresh future date | skips the provider   | uses cached date     |
```

Test mapping: `EarningsEventRecorderTest` covers R2-1 through R2-4.

Test mapping: `DashboardViewModelTest` covers stale entries reaching the owner.

### Report date choice

```gherkin
Scenario Outline: Positions chooses the nearest eligible report date
  Given a ticker has <log evidence> and <calendar evidence>
  When Positions projects the row on the New York session day
  Then the row uses <selected source>
  And its closeness is <closeness>

Examples:
  | Case | log evidence                 | calendar evidence | selected source | closeness |
  | R3-1 | settled past and future log | later date        | nearest future log | Tomorrow |
  | R3-2 | settled past only           | later date        | calendar          | Later    |
  | R3-3 | two future log dates        | none              | nearest log      | Tomorrow |
  | R3-4 | same-day settled print      | later date        | same-day log     | Today    |
```

Test mapping: `PositionsPresentationTest` covers R3-1 through R3-4.

Test mapping: `PositionsPresentationTest` also covers New York date conversion.

## Test evidence

The initial red run found three recorder failures and one same-day settled regression.

The first green run passed 99 ViewModel tests, 23 Positions tests, and 74 recorder tests.

The final parent run passed 122 ViewModel and Positions tests.

The final run covers the required non-null New York day passed to every date helper.

The final Positions run also covers settled history beside a future log date.
