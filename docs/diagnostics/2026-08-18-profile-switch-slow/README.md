# DEF-08: the profile switch waited for a network call nobody asked for

**Status: fixed 2026-08-18.** A profile switch made while a refresh was in flight cost a whole Yahoo
call before the new profile reached the screen: **1 185 ms against 44 ms idle**, with a fetch of
600 ms. It now costs **26 ms against 16 ms idle**. A second defect, found by the same measurement,
held the first refreshed row off the screen until eight rows had landed.

Reported by Juan, 2026-08-18, on the Android app. Three symptoms:

1. Changing profile takes a very long time.
2. The new profile does not load well.
3. The refresh takes a long time to start, then advances slowly.

## The rule this file follows

Measure first. The plan said so, and the week lost to the Compose hang
([2026-08-11](../2026-08-11-compose-test-hang/README.md)) is why: two causes were named by reading
the code, and both were wrong. So four suspects were written down, and none was touched before a
reading existed.

No live Yahoo calls anywhere below. Local stages run against an offline provider. Network mechanics
run against a provider that holds its thread with `Thread.sleep`, which is what a socket read does
to the coroutine that joins it. A suspending `delay` would not do: it cancels at once, and would
report a mechanism the app does not have.

## Step 1 — what the local path costs

`ProfileSwitchTimingBenchTest`. Offline provider, isolated database, Robolectric.

| Stage | merval cold ms | dow warm ms | sp500 ms |
| --- | --- | --- | --- |
| `switch.resolve-symbols` | 3 | 6 | 5 |
| `switch.load-warm-start` | 3 | 26 | 16 |
| `switch.adopt-profile` | 3 | 3 | 33 |
| `switch.to-first-emit` | 11 | 36 | 55 |

**Suspect 1 is cleared.** The whole local switch, catalog read and SQLite warm start and the work
under `stateMutex` together, lands at 55 ms on the largest profile in the app. It cannot be what a
user feels as "a very long time".

The refresh round, 30 symbols, provider answering at once:

| Reading | ms |
| --- | --- |
| first symbol applied | 133 |
| round total | 328 |
| per symbol, fastest / middle / slowest | 0 / 0 / 19 |

**Suspect 3 is left alone.** Per-symbol local work is 0 ms at the middle. The round is bound by the
network, so `REFRESH_CONCURRENCY = 4` is not what makes it slow — and raising it is a bet on a Yahoo
rate limit that cannot be measured without live calls. It stays at 4. Symptom 3's second half
("advances slowly") is therefore **not answered by this work**; it needs a rate-limit reading that
this repo is not allowed to take.

## Step 2 — the cause of symptoms 1 and 2

`ProfileSwitchUnderRefreshProbeTest`, blocking provider at 600 ms per fetch:

| Switch | ms |
| --- | --- |
| nothing in flight | 44 |
| fetches in flight | 1 185 |

**Suspect 2 confirmed.** `beginProfileSwitch` called `cancelActiveProfileWork()`, which took the
running jobs and `cancelAndJoin()`ed them — and that join sat in front of the first `emitUpdate`.
`cancel` returns at once. `join` does not: a coroutine parked in a socket read ends when the read
ends, whatever it was told. So the switch paid for one whole Yahoo call before it published
anything, and while it paid, the screen held the old profile. That is symptom 1 and symptom 2 in
one mechanism.

## The fix

Publish the new profile first, join the abandoned work off the path the user waits on.
`cancelActiveProfileWorkAsync()` cancels and hands the jobs back still running; the switch emits;
the joining moved into the hydrate job.

| Switch | before | after |
| --- | --- | --- |
| nothing in flight | 44 ms | 16 ms |
| fetches in flight | 1 185 ms | 26 ms |

The second defect the readings exposed: `EMIT_UPDATE_BATCH = 8` was applied from the first row of a
round. A batch of eight is right for the middle of a round and wrong at its start, where it holds
the first result until seven more land. Over a network that is seconds of a screen that shows
nothing new. The first row now publishes on its own.

## Why the abandoned work cannot corrupt the new profile

The old code was safe by construction: it never let two rounds live at once. The new code does, so
the claim needs evidence, not reading.

`the_fetches_of_the_profile_left_behind_bring_nothing_into_the_new_one` switches away while fetches
are in flight and then reads the engine's own symbol set. Measured with the cancel and the deferred
join both removed, so the abandoned round truly runs to its end beside the new profile:

- generation guard removed as well: `[T, CI, AAPL, AMZN, BMA.BA, GGAL.BA, YPFD.BA, TECO2.BA]` —
  four symbols of the profile the user left, in the one they are looking at.
- generation guard kept: clean.

The guard alone carries it. The generation is bumped under `stateMutex` before anything is
cancelled, and every refresh result is dropped unless its generation is the active one.

## What each test can see

| Test | Goes red when |
| --- | --- |
| `a_switch_does_not_wait_for_the_fetches_of_the_profile_it_leaves` | the join returns in front of the first emit |
| `the_first_refreshed_row_is_published_before_the_batch_is_full` | the first row waits for a full batch |
| `the_fetches_of_the_profile_left_behind_bring_nothing_into_the_new_one` | an abandoned round writes into the live profile |

Each was reddened by its own single mutation and green again after it was undone. The two bench
tests assert almost nothing on purpose: they print, and their thresholds would be readings of the
build machine.

## Leaving the test JVM clean

The whole Android suite runs in one JVM, and Robolectric drops its database connections the moment
a test returns. A coroutine of this bench still alive then fails on a pointer that no longer exists,
and the failure is reported against whichever class runs next — which is how
`ScoringPreferencesStoreTest` and `RefreshPersistenceBackPressureTest` came to fail for a change
that does not touch them.

Cancelling once is not enough. A blocking fetch holds its thread whatever is asked of it, and the
enrichment that a cancelled refresh starts in its `finally` is born after the first cancel. The
probe cancels, waits out the longest fetch, and cancels again.

## Symptom 3, second half: the first load, 2026-08-18

The switch was fixed above. The first load was not, and Juan reported it again: too slow, with a
stated suspicion of eager DCF work and a claim that it used to be ten times faster.

### The bench that was wrong first

`RefreshWindowBenchTest` was written against the `dow` profile and ran past the three-minute Gradle
task timeout with a forty-millisecond fake latency. That reading looked like proof that local work
dominates. It was not a reading at all: `bootstrap` fills the list from the database and starts no
refresh, so the bench sat in its own polling loop and fetched nothing. `symbolsDone` was zero.

The bench now calls `refreshAll` and prints the counters, so a run that fetches nothing says so.

### What the load costs

`qa` profile, 20 symbols, a fake server that accepts 16 calls at once and answers each in 40 ms.

| Reading | Fixed window of 4 | Adaptive window |
| --- | --- | --- |
| Peak calls in flight | 4 | 8 |
| Round | 590 ms | 425 ms |
| Whole load | 1 411 ms | 1 211 ms |

The window was the limit, not the local work: only 91 ms of the 590 ms round was spent outside the
fetches. The provider would have taken sixteen at once and was asked for four.

### The regression Juan remembered

`git log -S REFRESH_CONCURRENCY` finds one commit: **d9effac2, 2026-07-09**,
`feat(android): Yahoo quoteSummary JSON provider and transparent WACC`. It took
`REFRESH_CONCURRENCY` from 8 to 4 and split the pipeline in two, giving the new enrichment pass a
window of 2. Two changes in one commit: half the concurrency, and a second serial pass behind it.

### What a refusal turns on

Juan's eager-DCF suspicion is real, and it is worse than eager. `fetchRefreshResult` calls
`resolveDcfFallback` only when `providerResult.snapshot == null`, and a rate limit is exactly what
makes the snapshot null. Against the same 20 symbols with a server that accepts two at a time:

| Reading | Fixed window of 4 | Adaptive window |
| --- | --- | --- |
| Calls refused with 429 | 32 | 24 |
| Fallback timeseries calls | 16 | 10 |

So the app asks for more work at the moment the provider is asking for less. The adaptive window
does not remove the fallback; it stops walking into the refusal that turns the fallback on.

### The fix

`AdaptiveRequestWindow` is the TCP rule, in `data/remote`. Slow start doubles the window each round
trip, a refusal halves it and sets the threshold there, and above the threshold it takes a full
window of good answers to open by one. A refused round closes the window once rather than once per
symbol, or eight refusals arriving together would collapse it to one.

A shrink takes back every free permit at once and books the rest against the calls still in flight,
so `size()` and the gate never disagree by more than the calls that were already out.

The refresh and the enrichment share one window. They ask the same host, so two windows would each
find the limit by hitting it, and each would blame the other's calls.

`REFRESH_CONCURRENCY` and `ENRICHMENT_CONCURRENCY` are gone.

## Loading that survives the background

Nothing in the app cancelled on background: the repository runs on its own scope and `viewModelScope`
outlives the activity going away. The platform is what stops it. A cached process gets no promise of
CPU and no promise of a socket, so a five-hundred-symbol load ends when the user leaves.

`RefreshForegroundService` (type `dataSync`) holds the process up, and `ForegroundLoadKeeper` starts
and stops it from one reading: `DefaultDashboardRepository.loadInFlight`. The enrichment is counted
in before the refresh is counted out, so the two halves of one load are one hold and the process is
never let go between them.

Android refuses a foreground service started from the background. The refusal is swallowed: a load
that cannot hold the process up still runs, and the user gets the old behaviour rather than a crash.

| Test | Goes red when |
| --- | --- |
| `a_load_that_is_still_fetching_says_it_is_running` | `loadInFlight` never turns true |
| `a_load_that_has_no_symbols_left_says_it_is_over` | `loadInFlight` never turns false |
| `a_load_reported_twice_is_still_one_hold` | a repeated reading restarts the service |

The first two were reddened by their own single mutation of `loadStarted` and `loadFinished`, one
mutation each, and each was caught by one test and not the other.

## What one symbol really costs

Juan kept reporting the load as far too slow, and said an older build reached about ten times the
tickers. The window work above cannot answer that. Its benches replace `YahooFinanceClient` with a
fake, so one symbol is one call by construction, and the call the load actually pays for is invisible
to them.

`CountingYahooHttp` sits under the shipped client instead, and `LoadCostProbeTest` counts every round
trip at the socket. A window divides the wait. It cannot remove a call the code makes.

| Cost per symbol, first load | Count |
| --- | --- |
| Yahoo round trips (quoteSummary, chart, timeseries) | 3.1 |
| SEC EDGAR companyfacts files | 1.0 |

The SEC file is the load. Measured over twenty real companyfacts files on disk:

| Reading | Value |
| --- | --- |
| Total on disk | 83.6 MB |
| Average per company | 4.28 MB |
| Largest | 8.6 MB |
| Kept after the sieve | 10.4 MB, 12% |
| Sieve CPU for the twenty | 1 486 ms on a desktop JVM, 74 ms each |

Projected onto `sp500` at phone latencies (Yahoo 300 ms, SEC 2 000 ms), 500 symbols:

| Reading | ms |
| --- | --- |
| Every symbol quoted, the list usable | 15 371 |
| Whole load | 68 318 |
| The SEC pass alone | 52 947 (77%) |

That is about 2.1 GB downloaded, written to the phone's flash, read back and sieved for one screen.

## SEC is asked when a symbol is opened

Juan's decision: SEC lazy only. The analyst target is the primary value everywhere, and our computed
price is a reference.

`DcfSourceCoordinator.resolve` now takes `allowSecondary`. The two bulk paths, `resolveDcfFallback`
and `enrichSymbol`, pass false, so the list resolves its DCF from the cheap Yahoo timeseries and
every row keeps a score. `ensureDetailLoaded` passes true once per symbol per session, recorded in
`secondaryAsked` and cleared with the rest of the in-memory state. Without that record a symbol SEC
has nothing for would pay the file again on every open, because its analysis stays on its Yahoo
source and still reads as needing resolution.

The anchor order was already what Juan asked for: `MarketSnapshot.intrinsicValueCents` is
`financialData.targetMeanPrice`, and `fairValueAnchor` runs analyst-weighted, analyst-consensus, DCF,
intrinsic model. Nothing was owed there.

`SecEdgarTimeseriesProvider.loadSievedFacts` also stopped writing the 4 MB file before reading it:
it sieves the response stream, and it sends `If-None-Match` from an ETag sidecar, so an unchanged
filing costs a 304 and keeps the slim file.

| Test | Goes red when |
| --- | --- |
| `a_first_load_downloads_no_companyfacts_file` | a bulk path asks SEC (20 of 20 symbols did) |
| `opening_a_symbol_downloads_its_companyfacts_file_once` | the detail stops asking, or asks twice |
| `refresh_fallback_never_downloads_a_companyfacts_file` | the refusal fallback asks SEC |
| `enrichment_never_downloads_a_companyfacts_file` | the enrichment asks SEC |
| `detail_load_uses_sec_when_yahoo_timeseries_is_not_dcf_usable` | the detail loses its audited source |

Each of the first two was reddened by its own single mutation: `allowSecondary = true` on the bulk
paths reddened both, and `allowSecondary = true` on the detail path reddened only the second.

## The second door to SEC

`allowSecondary` covered the DCF coordinator only. `residualFromDrivers`, the residual-income chain
for banks and insurers, went to SEC on its own and was called from three bulk sites. With the gate
opened again on the refresh site alone, 29 of the 30 dow symbols downloaded a companyfacts file.

`residualChainRan` now records only a run that really asked SEC. That set means "SEC was tried and
gave nothing", and the locked path turns it into a terminal answer, so a skipped run must not enter
it.

`a_financial_services_symbol_in_the_load_downloads_no_companyfacts_file` holds it. Each bulk site was
mutated on its own and each mutation reddened it.

## The network layer

Four defects, all measured before anything was written against them.

**OkHttp bounds nothing.** The guess was that `Dispatcher.maxRequestsPerHost = 5` sat under the
window and made every window reading a reading of a queue. Measured: 40 calls in flight on one host.
`maxRequestsPerHost` bounds `enqueue()`, and this app calls `execute()`, which connects on the
calling thread. The hypothesis was false. `HttpDispatcherCeilingProbeTest` now holds the measured
fact instead, so a move to `enqueue()` reddens it.

**A permit covered a symbol, not a request.** One symbol is 3.1 round trips, so a window of eight
was up to 24 calls in flight. The controller was steering by a number it never measured.

**The retry was inside the permit, and it slept.** Four attempts of `Thread.sleep`, up to 12 s each,
holding both a permit and a `Dispatchers.IO` thread. The window learned only the last of four
outcomes.

**`Retry-After` was parsed and dropped.** The clearest instruction a provider can give, answered by
halving the window and asking again.

`RequestGovernor` owns all four: one permit per request, every wait a suspension, and a `Retry-After`
that holds the whole provider and not just the call that hit it. Backoff is full jitter, so a round
of refused calls does not come back as one round of refused calls. Yahoo and SEC have one each,
because sharing would let one host's refusal close the other host's window. SEC's is smaller, four,
because a companyfacts file is about 4 MB, and it reads SEC's 403 with `Retry-After` as push-back
rather than as a permanent refusal.

`ProviderHttpClient` fixes two more. The connection pool held five idle connections against a window
of up to 24 on one host, so every call past the fifth paid a fresh TCP and TLS handshake; it now
holds one per call the window can have in flight, on each of the three hosts. And only the whole
call was bounded, at 20 s, so a socket that connected and said nothing held its permit for the full
20; connect and read are now bounded on their own.

`YahooSession.ensureCrumb` was a `synchronized` monitor across two network calls with a 12-minute
TTL, so every expiry parked an OS thread per waiting symbol. It is a coroutine `Mutex` now, and the
thread goes back to the pool while the caller waits.

`DefaultDashboardRepository` no longer holds permits. `flatMapMerge` is fan-out and the governor is
the only controller, so there is one place that decides what the provider is asked.

### What the change measures

`GovernedLoadTest`: 20 symbols against a server that serves 2 at once and answers the rest with 429
and `Retry-After`, over the real client.

| Reading | As it ships | Retry back inside the permit |
| --- | --- | --- |
| Rows live | 20, 20, 20 | 20, 20, 20 |
| Refusals | 12, 13, 13 | 30, 41, 41 |

The rows reading is the same either way, so it measures nothing about the redesign and the test says
so; it is kept because a short load is a product defect. The refusals reading is the one that
measures it, and the spread inside each design is at most one call, far under the gap.

## The local pipeline, 2026-08-18

`SnapshotCostBenchTest`: `sp500`, 250 candles a chart, an offline provider that answers at once, and
a collector that does what the view model does (`observeUpdates().collectLatest { currentSnapshot }`).
Every millisecond is local: fetch, apply, persist, snapshot. Robolectric SQLite goes through a JNI
shadow on every bind and every column read, so the persist readings are high against a phone; the
shares are what carry over.

| Reading | Before | After |
| --- | --- | --- |
| whole load | 13.5 s | 6.1 s |
| refresh round | 7.7 s | 3.7 s |
| snapshots built | 131 | 127 |
| snapshot, middle / sum | 35 ms / 4.6 s | 15 ms / 1.9 s |
| plan boards inside a snapshot, sum | 2.6 s | 0.3 s |
| `refresh.persist`, sum | 5.3 s (9 ms a symbol) | 3.5 s (7 ms a symbol, in batches of eight) |

Three causes, in the order the numbers named them.

**Every tick evaluated every dip setup again.** `snapshotLocked` runs under the state mutex on every
update tick and rebuilt three plan boards from scratch: `DipSignalEngine.evaluate` and
`LeftoverSignalEngine.evaluate` over all 500 rows, 130 times a load. `DipSetupMemo` keeps the last
`DipRowInput` and its setup per symbol; an unchanged row is a `data class` equality on cached list
references and costs nothing. Cleared with the chart caches.

**One write per symbol, on the collector's critical path.** `processRefreshRound` and `runEnrichment`
awaited `persistDelta` after every symbol, so the round could go no faster than one SQLite
transaction per row. `PendingDeltas` gathers the deltas and one write lands per emit batch of eight,
plus one at the end of the round. `RefreshPersistenceBackPressureTest` still reads a peak of one
persist in flight, because the collector still awaits the write; there are eight times fewer of them.

**A chart was deleted and rewritten whole.** `persistPricingCandles` did `DELETE` the range and
`insertWithOnConflict` with a `ContentValues` per candle, on every refresh, for the same year of
candles it wrote an hour before: six of the eight milliseconds a symbol cost to persist. It now reads
the range, merges, and writes only the candles that are new or changed through one compiled
statement; the newest candle is always written so `captured_at` still says when the range was
fetched. On a warm refresh that is one row. `replaceIssues` did delete-all plus insert-each on every
batch for a list that changes on a failure; the store keeps the last list it wrote and skips an
equal one, and forgets it in `resetWarmStartState` where the table is emptied.

### Where the round's time is now

The collector's path is the persist: 3.5 s of the 3.7 s round, and inside a cold write of `sp500`
the chart rows are the most of it, 37 candles a symbol that did not exist yet. Under Robolectric a
compiled insert costs about 130 µs a row; on a phone it is one native call. The next levers, none
taken:

- A serial writer coroutine over a bounded channel would take the write off the collector's path
  while keeping one persist in flight. In this bench it gains little, because the write is the
  slowest stage either way; over a network it hides the write behind the wait.
- A two-pass round, every quote before any chart, would halve the time until the list is fully
  quoted: `LoadCostProbeTest` reads 1 505 round trips for `sp500`, of which 500 are quotes, and the
  window is shared. That is the largest reading left, and it is a change to what a refresh result
  is, so it is not made here.
- `pricing_candle_symbol_range_idx` duplicates the table's primary key, so every insert maintains
  two indexes for one lookup. Dropping it is a schema step.
- `screenDataProjectionRequestLocked` copies every revision list and chart summary map on every
  tick, 4.7 ms a snapshot, so a projection that outlives the lock cannot see a later mutation. It is
  the largest part of a snapshot now.

### What each test can see

`SnapshotCostBenchTest` prints the table above and asserts only that a snapshot was built. The
timers it reads are `timedStage("snapshot.build")` and the `timedPart` sub-stages inside
`snapshotLocked`, plus `refresh.apply` and `refresh.persist`. `RefreshPersistenceBackPressureTest`
reddens if a batch is ever handed to a launch instead of awaited. `DipSetupMemoTest` counts
evaluations: one for a repeated input, two for a changed one, two again after `clear`; a memo that
misread a change would show a stale board and pass every board test. `PlanBoardAssemblerTest` and
`LeftoverBoardAssemblerTest` run the assemblers with the default evaluator, so the memo cannot change what a board
says, only when it is computed.

## What the user waits for, 2026-08-18

Juan's report, against the build of 2026-08-04: a switch away from a loading profile does not start
the new one; when it starts, the first row takes an eternity; a ticker opened during the load waits
until the loop reaches it. The build of 2026-08-04 had four symbols in flight and no governor. This
one had a window of 24, a `Retry-After` that holds the whole provider, and a first-come line in front
of every permit. Each symptom is a wait behind the bulk load, and `InteractiveLoadProbeTest` measures
all three the same way: the shipped client over a server that answers in 300 ms, serves six calls at
once and refuses the rest with 429, with the 501-symbol profile loading for three seconds first.

### The four causes, each measured

**The switch joined the calls it had cancelled.** `switch.join-abandoned-work` waited for every
in-flight call of the old profile before the new one touched the wire, and `Call.execute()` does not
end on a coroutine cancel: it holds its thread, its permit and its socket until the answer or the
20 s call timeout. Measured: `selectProfile` 212 ms with the join, 50 ms without.

**Nobody had a place at the front of the line.** The window is a semaphore, and under a load of 501
its line is 24 deep at all times. `ensureDetailLoaded` for the last symbol of the profile took
4 655 ms behind the load with a healthy server, and 13 467 ms behind one that sends `Retry-After`.

**Every symbol was quoted and charted back to back.** So the list was fully quoted only when the last
chart had landed. Measured in `LoadCostProbeTest` on the 501-symbol profile: 14 584 ms until every
symbol was quoted, in one pass.

**A cancelled refresh skipped its own bookkeeping.** The `finally` that counted the load out and
cleared `activeRefreshJob` suspended on the mutex, and the first suspension in a cancelled coroutine
throws, so a switched-away load stayed counted in and the process stayed pinned to the foreground.

### The fix

- `PriorityGate` replaces the semaphore inside `AdaptiveRequestWindow`: two lines, and the next
  permit goes to the urgent line first. `InteractiveRequest` is a coroutine-context marker;
  `RequestGovernor` reads it and asks for the urgent line. `ensureDetailLoaded`, `searchTickers`
  and `ensureReplayBackingLoaded` run under it. An urgent call still waits out a `Retry-After`,
  because the provider said no to everyone.
- `Call.executeCancellable()`: `execute()` on the calling thread, with `Call.cancel()` on coroutine
  cancel, so a cancelled fetch gives its permit back in milliseconds. `enqueue()` stays out; see
  `HttpDispatcherCeilingProbeTest`.
- The switch cancels the old profile's work and starts the new one at once. Nothing joins. The
  generation guard drops every result of the old profile. The market read is tracked and cancelled
  with the rest, and its `refreshing = false` runs under `NonCancellable`.
- The refresh runs in two passes: every quote, then the year chart of every symbol the first pass did
  not chart. The first pass still charts a symbol whose quote came back empty, because the fallback
  for a missing quote is built from the chart's last close. The chart pass reuses the enrichment's
  round runner and apply, so it retries and records issues the way the enrichment does.
- The refresh and enrichment jobs do their bookkeeping under `NonCancellable`; the enrichment is
  started only after a refresh that ran to its end.

### Before and after

Same server (300 ms, six at once, 429 past that), same profile, single runs; the noise is about one
round trip.

| Reading | Before | After |
| --- | --- | --- |
| `selectProfile` returns | 212 ms | 50 ms |
| First quote of the new profile after the switch | 214 ms | 194 ms |
| Every symbol of the new profile (23) quoted | 1 168 ms | 1 448 ms |
| `ensureDetailLoaded` of the last symbol, under load | 4 655 ms | 1 790 ms |
| Every symbol of the 501 quoted (`LoadCostProbeTest`) | 14 584 ms | 8 373 ms |
| Whole 501 load | 25 564 ms | 25 727 ms |

The whole load is unchanged: the two-pass moves the quotes in front of the charts and adds nothing.
The 23-symbol readings are one round trip apart, inside the noise.

**Against a server that answers every refused call with `Retry-After: 5`** the readings do not
move: first quote 3 043 ms after the switch, detail 13 467 ms before and 14 045 ms after (re-measured on the final code). That is
the provider hold, honoured on purpose for every call, and it dominates everything else while the
window oscillates over the server's limit. Whether Yahoo sends `Retry-After` on its 429s is not
known from this repository, and no live call is made from a test to find out. If the phone still
shows the eternity, `RequestGovernor.cooldownRemaining()` is the first number to read.

### What each test can see

`InteractiveLoadProbeTest` asserts the first quote of the new profile leaves under 600 ms after the
switch and that the opened symbol is back under 1 800 ms; both were red before the fix under the
same server. `PriorityGateTest` drives one hand-over at a time under a test scheduler: the urgent
waiter is served before an earlier ordinary one; a cancelled waiter gives its place back; a waiter
cancelled as its permit is handed over passes the permit on, so a permit is never lost to a cancel.
`RefreshPersistenceBackPressureTest`'s offline fake now refuses the chart fetch too, because the
chart pass reaches it where the one-pass refresh did not.

## On the emulator, 2026-08-18 evening

The first run on a device, `emulator-5554`, against live Yahoo (a manual device run; every test
above stays offline). Juan's report during it: "va mega lento de 8 en 8 nomás, antes hacía de a
20-30". Four readings answered it.

### Yahoo sends no `Retry-After`

Every 429 came with `retryAfterMillis=null`. So the whole `Retry-After` path above was idle on the
phone, and a refused call was retried on the governor's own backoff, four attempts a call, twenty-four
calls at once: **a storm of refused calls, eight symbols landing per Yahoo reopen**. That is the "8 en
8".

`RequestGovernor` now holds the provider on its own after a refusal without a `Retry-After`: 1 s,
then 2, 4, 8 s, reset on the first good answer, honoured before and inside every permit. Measured on
the device: while Yahoo is closed, one probe every 8 s; Yahoo reopens about 70 s after a trip; the
window grows back by slow start.

`a_quota_that_says_no_without_a_retry_after_is_not_hammered` holds it: 20 symbols against a server
that refuses everything past 10 calls with a bare 429; the quiet window after the first refusal must
hold at most one probe. It was red at 24 calls before the ladder.

### The quota, measured

| Reading | Value |
| --- | --- |
| Quotes served before the trip, `russell` cold | 1 647 in 73 s (22 a second) |
| Reopen after a trip | about 70 s |
| Served per reopen after that | 250 to 300 calls |

So a `russell` load of 1 937 quotes plus 1 937 charts is bounded by Yahoo's call count, and no window
setting changes that. The remaining lever is fewer calls: the batch quote endpoint (`v7/finance/quote`,
about a hundred symbols a call) would make the quote pass twenty calls, and the daily chart the market
read already fetches would give the weekly chart of the list without a second call. Neither is done.

### The market read froze the store for 43 s

`MarketDataRepository.refreshIfStale` (added 2026-08-09, b2cb8930) fetched a 1y daily chart for every
tracked symbol beside the refresh, through the same governor, and then wrote every bar back — 1 937
symbols × 250 bars through `insertWithOnConflict` with a `ContentValues` each — in **one
transaction**. `dumpsys dbinfo` and the system log named it:

```
W/SQLiteConnectionPool: The connection pool for discount_screener_state.sqlite3 has been unable to
grant a connection to thread 188 ... sql="INSERT OR REPLACE  INTO pricing_candle(...)"
stage-timing stage=refresh.persist ms=43256
```

Every quote batch of the refresh waited behind it. Two changes:

- `persistBacktestCandles` writes the delta: the stored bars from the oldest incoming date are read
  once a symbol, only new or changed bars are written through one compiled statement, the newest bar
  always, and the connection is given back every 100 symbols. On a warm run that is one row a symbol.
  `a_refetch_does_not_rewrite_a_bar_it_did_not_change` reads `captured_at` of an old bar after a
  refetch; it went red when the filter was replaced by the whole incoming list.
- The market read starts from `finishRefresh`, after the quotes and the charts, beside the enrichment.
  Started beside the refresh it doubled the calls on the wire while the list was filling. The Market
  tab's reading therefore lands after the list, which is the order the user looks at them.
  `MarketReadStatusTest` runs against `CountingYahooHttp` now, offline, and waits for the read.

### The chart pass waited for a straggler

`runRefresh` ran the chart pass after the last retry round of the quote pass, and one symbol that
answers 503 every time holds three retry rounds behind it: 1.5, 4 and 8 s of backoff plus four
governor attempts each. Measured on the device: twenty-four seconds of idle wire behind one symbol.
The chart pass now starts right after the first quote round, beside the retry rounds. A symbol
quoted late is charted twice; one chart is the price.
`the_charts_do_not_wait_for_the_retry_rounds_of_a_straggler` read a 40 962 ms gap before and
3 980 ms after.

### Device readings, final build

| Reading | Value |
| --- | --- |
| `sp500` launch: 504 quotes | 13 s, no refusal |
| `sp500` charts after the quotes | 24 s |
| `sp500` market read after the charts | about 15 s, COMMIT 39 ms |
| Switch `sp500` → `russell`, to first emit | 971 ms for 1 937 symbols |
| `russell` quotes | 1 647 in 73 s, then a 70 s Yahoo hold, all 1 937 at 2 min 28 s |
| Longest `refresh.persist` | 107 ms (was 43 256 ms) |
| `SQLiteConnectionPool` warnings | none |

## The batch quote endpoint, 2026-08-18 night

The load is bound by Yahoo's call count (see "The quota, measured"), so the lever left is fewer
calls. `v7/finance/quote?symbols=A,B,C` answers many symbols in one call. Measured live before any
code was written:

| Probe | Reading |
| --- | --- |
| The whole `russell` list, 1 937 symbols, one call | HTTP 200, 4.6 MB, 0.68 s, URL 9 117 chars |
| 100 symbols | 240 KB, ~0.6 s |
| 120 consecutive 100-symbol calls | all 200, no hold |
| Without cookie + crumb | HTTP 401 `Unauthorized`, an error document |
| `BF.B` asked as-is | an empty shell without a price; asked as `BF-B` it prices |
| Live equities the endpoint does not serve at all | 65 of 1 937 (SATS, GTLS among them), alone or in a batch |

What a row carries: `regularMarketPrice`, `previousClose`, `marketCap`, `sharesOutstanding`,
trailing/forward PE, `priceToBook`, `epsTrailingTwelveMonths`, `earningsTimestamp*`, `longName`,
`averageAnalystRating`, all as bare numbers. What it does not: `targetMeanPrice`, the target range,
`numberOfAnalystOpinions`, the recommendation counts, sector and industry, `financialData`. A
`MarketSnapshot` needs the target, so the endpoint cannot make a row from nothing. It can refresh the
price of a row the store already knows.

### Pass zero of the refresh

`YahooFinanceClient.fetchQuotes(symbols)` asks in batches of 250 (~600 KB a call, so the first rows
land in the first second and the phone parses one answer while the next is on the wire), under
`yahooRequestSymbol` and mapped back. `runRefresh` calls it first, in `primeWarmPrices`, for every
symbol the engine already has a detail for; the per-symbol pass then serves the rows the batch could
not price first. A batch-priced row keeps its restored target, signal and fundamentals and keeps
reading as restored until its own `quoteSummary` lands: the price is fresh, the valuation around it
is not, and the label says the latter. The advisor's P0 on the first design was exactly this: a
row with a fresh price and a stale target must not read as Live.

Adaptive against the rate limit, at two levels. The governor was already adaptive: AIMD on the
concurrency window and the 1/2/4/8 s hold ladder on a 429 without `Retry-After`; a batch call goes
through the same governor as every other call. What is new is in the client: a batch Yahoo refuses
outright (a client error or a body that is not a quote answer) is split in two and each half asked
again, down to 8 symbols; below that the symbols are left to the per-symbol path. A batch still
refused after the governor's attempts is dropped and logged, never thrown, so a closed Yahoo costs
a slower load and never a failed one.
`a_refused_batch_is_split_until_the_server_answers` runs a server that answers 400 above 12
symbols; twenty symbols all come back priced.

### On the device

| Reading | Before | After |
| --- | --- | --- |
| `sp500` warm launch, every restored row at today's price | 13 s (last quote) | 3.2 s, first 249 rows at 1.6 s, 2 batch calls |
| Switch `sp500` → `russell`, 1 790 restored rows at today's price | 2 min 28 s (last quote) | 5.95 s, 8 batch calls, 1 769 priced |
| Rows the batch left to the per-symbol pass | — | 21 of 1 790 (1.2%), the endpoint's own gaps |
| Batch calls refused | — | none; the 429 hold arrived 40 s later, in the per-symbol pass, and the governor closed the window as before |

### What each test can see

- `QuoteBatchTest`: the parser on a live capture (`yahoo/quote/batch.json`: a priced row, a
  symbol mapped back from `BRK-B`, the `BF.B` shell, the absent `SATS`), the 401 error document
  refused rather than read as empty, and the split-on-refusal against a fake server.
- `a_refresh_prices_restored_rows_from_the_batch_endpoint_while_they_still_read_as_cached`: a
  Yahoo whose batch answers and whose `quoteSummary` never does; the restored row shows the batch
  price and reads `Cached`. It goes red when `applyWarmPriceLocked` stops ingesting the snapshot.
- `the_quote_pass_asks_for_the_rows_the_batch_did_not_price_first`: goes red when the reorder in
  `runRefresh` is removed.
- `CountingYahooHttp` answers `/v7/finance/quote` at 77.25 a symbol, so the probes that run the
  real client see the batch calls beside the rest.

## The same-day reopen, 2026-08-19

### The contract

A row's data is a day's worth of data. Reopening the app inside the day buys the batch price and
nothing else; the Refresh button is a forced refresh and buys everything again.

- The mark is per symbol: `loadRefreshMarks` reads, for each symbol, the time of its last
  `quoteSummary` capture (`snapshot`, no scope), its last chart capture and its last
  fundamentals-timeseries capture. A capture younger than `FRESH_CAPTURE_SECONDS` (one day) is
  kept; `freshCaptureSkip` turns the marks into `FreshCaptureSkip(quotedAt, chart, timeseries)`.
  `force` hands `runRefresh` an empty skip.
- The batch price is filed under `snapshot:batch-quote` and is never the mark. It lands on every
  warm row at every launch; if it counted, the mark would roll forward for ever and no row would
  buy its own quote again.
- A kept row reads **Restored, "saved Nh ago"** with the time of its own quote. It never reads Live
  or Updated: `keepRowsLocked` puts it in `keptSymbols`, and the projection checks `keptSymbols`
  before `refreshedSymbols`. Its own quote, when it lands (a forced refresh or the next day),
  removes it from `keptSymbols` and it reads Live as before.
- The banner counts only the rows the refresh must quote: `RefreshStarted(symbolCount)` is
  `symbols.count { it !in skip.quote }`, and `RefreshProgress.total` is the per-symbol pass.
- Charts inside the day come from the file (`restoreYearChartsFromFile`, one store read at the
  start of the refresh); `runRefresh` leaves `skip.chart` out of the `uncharted` pass.
- The DCF is recomputed from the file. `timeseriesOnFile` reads the symbol's last
  fundamentals-timeseries capture and `DcfSourceCoordinator.resolveFromFile` runs the engine on it
  with today's market params; no provider is asked. Outside the day, or on force, `resolve` asks
  the providers as before and returns a `DcfResolution`: the selection and every timeseries a
  provider sent, usable or not.

The last point is where the reopen was still slow. Measured on the device DB on 2026-08-19
(`sp500`, 497 rows): 368 rows had a DCF of "valuation unavailable" (354 of them "marginal tax is
unavailable after filing and jurisdiction sources"), and the whole DB held 8 fundamentals-timeseries
captures, 1 of them for a tracked row. `fundamentalTimeseriesCapture` filed only the timeseries
the engine had valued, so a rejected timeseries was never on file and each of those 354 rows cost a
Yahoo timeseries call at every launch. `fundamentalTimeseriesCaptures` now files every provider
answer under the provider that sent it (`scope_key` = `YahooFinance` / `SecEdgar`); after one launch
of the new build the DB holds 439 captures for the 497 rows, and the next launch recomputes them
from the file.

### Who reads the freshness sets

The label is one reader. The rest, so that the next change knows what else moves:

- `staleSymbols`: `cachedSymbolCount` in the snapshot; the Restored / Stale projection categories;
  the row `stale` flag (`DashboardLists` shows it); the `stale` flag on each `MetricGroupStatus` in
  the detail; hydration adds to it. A kept row stays in `staleSymbols` until its own quote lands.
- `refreshedSymbols`: the Live projection category, `TrackedRowState.Live`, `isRefreshed` on the
  row and the detail; added when a `quoteSummary` is applied.
- `keptSymbols`: the Restored projection and `rowFreshnessFor`; cleared at `startRefresh` and on
  profile switch; removed per symbol when its own quote lands.

### On the device

Emulator-5554, live Yahoo, `sp500` (497 rows), reopened inside the day. "Before" is the build of
2026-08-18 night (batch prices, then every quote, chart and timeseries again).

| Reading | Before | After |
| --- | --- | --- |
| Rows on screen from the file | about 4 s after process start | the same (the store read, see "The local pipeline") |
| Rows at today's price | 3.2 s after the refresh starts | 3.0 s, first 246 rows at 1.6 s, 2 batch calls |
| `quoteSummary` calls | 504 | 2 (`EA`, `SATS`: the rows the batch did not price) |
| Year chart calls | about 500 | 3 (marks older than a day) |
| Fundamentals-timeseries calls | about 354 (every "unavailable" row) | 0; the DB filed 439 captures at the first launch of the build and none at the next |
| Label on a kept row | — | `Restored saved 54m ago`, the time of its own quote; it did not move across three launches |
| Refresh button | — | forced: 504 `quoteSummary` calls, prices done at 1.5 s |
| `refresh.charts` stage | 24 s | 13.8 s, in the background: the straggler's retry ladder behind 3 charts; rows already on screen |


### What each test can see

- `SameDayRefreshTest` (Robolectric, a real store, 20 symbols, movable clock and market params):
  - `a_same_day_reopen_keeps_a_row_restored_with_the_time_of_its_own_quote`: red when
    `keptSymbols += kept` is removed.
  - `a_same_day_reopen_buys_no_quote_and_no_chart`: red when the `uncharted` pass stops honouring
    `skip.chart`.
  - `a_same_day_reopen_counts_only_the_rows_it_must_quote`: red when `RefreshStarted` counts every
    row.
  - `a_forced_refresh_buys_every_quote_again`.
  - `a_batch_price_does_not_move_the_day_a_row_was_quoted`: a reopen at +3 h then at +25 h; red
    when the batch price is filed under the plain `snapshot` key.
  - `a_dcf_the_market_moved_is_recomputed_from_the_file_with_no_call`: rf 430 → 431 bps between
    launches; red when `timeseriesOnFile` reads nothing.
  - `a_row_the_engine_could_not_value_is_not_asked_for_again_inside_the_day`: red when the file
    keeps only what was valued, and when `timeseriesOnFile` reads nothing.
- `RefreshMarksTest`: the `quoteSummary` capture is the mark; a `snapshot:batch-quote` capture
  after it leaves the mark where it was.
- `DcfSourceCoordinatorTest.resolve_keeps_a_timeseries_the_engine_rejected`.
- `WarmLaunchCostProbeTest`: the second launch of a warm profile, through the real client, makes
  one batch call and no `quoteSummary`, chart or timeseries call.
- `not_eligible_reopens_when_fundamentals_fingerprint_changes` now recovers through a forced
  refresh: inside the day a plain refresh judges the file's copy of the rejected timeseries.

Each mutation above was applied alone, run against the whole group, and reverted.

## Update 2026-08-29: the sieve keeps a fact, not a concept

The readings above measure the first sieve, which kept a whole concept once the concept was on the
driver list. Every reader then dropped most of what it parsed: a quarter, a form that is not a
10-K, and a dimensional breakdown are all refused after the parse. So the phone paid RAM, flash and
parse time for rows it always threw away.

The sieve now applies that same filter on the stream, and keeps only the seven fields a reader
reads. `accn`, `fy`, `frame`, and the concept `label` and `description` never reach the output.

Measured on Apple's real companyfacts file, 3 789 099 chars. The reading is a desktop JVM, fastest
of five, so it is not comparable with the emulator timings in the tables above. It is the same
machine before and after, which is what the comparison needs.

The 12% in the table above is the average of the twenty-file corpus. Apple's file keeps 15.7%, so
the two numbers measure different populations, not a change:

| Reading | Before | After |
| --- | --- | --- |
| Chars kept | 594 967 | 128 104 |
| Share of the source | 15.7% | 3.4% |
| Sieve time | 61 ms | 25 ms |

The kept chars are what the string, the cache file and the parsed tree that follows them all cost a
multiple of. The time falls because the reader stopped crossing a decoder lock per char.

`JsonStreamReader` also reads the source in 16 KB blocks. It read one char at a time before, and
the network reader crosses a decoder lock on every one of a companyfacts file's four million chars.

`SecIssuerComponentClient` sieves the response stream too. It read the whole 4 MB body to a string
first, which spent the memory the sieve exists to save.

That client built its own `OkHttpClient` inside the constructor, so no test could reach the new
path. It now takes the client as a defaulted parameter, and `cannedHttpClient` answers a named URL
with one body. A streamed body has no string seam above it, so the double sits under the client.

The whole lookup runs on that seam now: the ticker map, the submissions list, the filing index, the
instance download, the subsidiary search and the sieved companyfacts, each pinned to its full URL.
A wrong URL fails the test with that URL in the message.

| Test | Goes red when |
| --- | --- |
| `the_sieve_keeps_under_a_fifth_of_the_source` | the sieve keeps a concept whole again |
| `the_timeseries_reader_reaches_the_same_series_through_the_sieve` | a cut changes the drivers, restatement precedence included |
| `the_residual_reader_reaches_the_same_drivers_through_the_sieve` | a cut changes the residual drivers on a real JPM or ACGL file |
| `a_concept_that_is_not_an_object_costs_only_that_concept` | one odd concept costs the whole company its facts |
| `a_units_that_is_not_an_object_costs_only_that_concept` | one odd `units` costs the whole company its facts |
| `a_quarter_never_reaches_the_output` | the period filter leaves the stream |
| `a_form_that_is_not_a_ten_k_never_reaches_the_output` | the form filter leaves the stream |
| `a_dimensional_fact_never_reaches_the_output` | a segment breakdown reaches the output |
| `a_null_segment_stays_in_the_output` | a null segment is dropped and a reader changes its mind |
| `the_fields_no_reader_asks_for_never_reach_the_output` | `accn`, `fy` or `frame` come back |
| `the_response_arrives_sieved` | the issuer client stops sieving what the network sends |
| `the_cache_holds_what_the_stream_returned` | the slim cache and the returned facts drift apart |
| `a_second_read_costs_no_request` | a cached companyfacts file asks the network again |
| `a_cache_older_than_the_ttl_is_read_again` | a day-old slim cache is served forever |
| `the_parent_facts_reach_the_operating_component` | a step of the lookup asks SEC for the wrong URL |
| `the_subsidiary_search_reaches_the_finance_component` | the subsidiary search or its companyfacts read breaks |
