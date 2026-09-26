# Unmerged Change Closure, 2026-09-26

The Android valuation audit is rejected for product integration. The ORCL research case is accepted as reproducible research.

These decisions close [issue #61](https://github.com/Thejuampi/discount_screener/issues/61).
No rejected item carries a delivery promise.

The [initial review](unmerged-change-review-2026-09-26.md) records the earlier branch decisions.
This record adds the final audit decision and the previously unreviewed stashes.

## Android Audit Decision

The review integrated the audit with `main` at `2e55af630e084cda63537571430d6e35cec9e8ec`.
The integration base is `98d986ed`. The final experiment is stash commit `0966fc8e43808fc5fa96853412729ac2bdd06d6b`.

The experiment repaired several defects:

- It removed the undated test bypass.
- It preserved typed SEC transport failures.
- It parsed dated, nonzero restricted award cohorts.
- It preserved current refresh ownership and cached source selection.
- It corrected the database upgrade and market-input cache identity.
- It moved source fixtures out of obsolete BMad paths.

Those repairs did not establish a safe production replacement.

| Finding | Evidence | Consequence |
| --- | --- | --- |
| Truncated filings claim complete coverage. | `omitted_material_exhibits_make_the_packet_incomplete` fails. The client keeps four documents and omits other material exhibits. | A valuation can omit obligations while claiming complete evidence. |
| Old requests replace newer stored evidence. | `an_older_completion_cannot_replace_newer_evidence` fails. A delayed request replaces a later decision timestamp. | The stored audit record can disagree with the visible result. |
| Evaluation swallows cancellation. | `evaluation_cancellation_stops_provider_resolution` fails. | A replaced request continues provider resolution. |
| Nonzero option inventories remain unsupported. | The normalizer always returns an empty `optionCohorts` list. | Issuers with options cannot satisfy the accepted award contract. |
| The success case uses constructed financial inputs. | The RICK assembler test supplies invented annual series and CompanyFacts rows beside real filing excerpts. | The test does not establish complete production coverage. |
| Financial issuers require operating-company capital fields. | Normalization requires cash, debt, and shares before model classification. | Residual-income analysis can fail on unrelated missing fields. |

The first integrated application run produced 967 tests, eight failures, and three skips.
After targeted repairs, 152 of 153 focused tests passed. The cancellation test still failed.
The two additional evidence tests both failed.

Five recorded filing samples matched upstream facts and normalized visible text:

- ADBE FY2025.
- ADSK FY2026.
- ADSK FY2025.
- CSCO FY2024.
- RICK FY2017.

These checks validate excerpt provenance. They do not validate complete issuer valuation coverage.
No device or live product QA ran.

The review rejects this combined production switch. Its recovered code remains an experiment with known failing tests.

## Stash Decisions

Stash indices change when Git adds a stash. Commit identifiers below remain stable.

| Commit | Contents | Final decision |
| --- | --- | --- |
| `0966fc8e43808fc5fa96853412729ac2bdd06d6b` | Audit integration repairs and counterexamples. | Reject product integration. Archive the experiment and its failing tests. |
| `74723fbffc6c2d2da0191b0afd152f67a6f755ef` | Old audit guides, plans, and documentation. | Reject obsolete policy and layout changes. Archive the historical record. |
| `e8c9791b91b7cf6b219dc410fe3c79d058f1ed88` | Three ORCL research files. | Accept under `lab/orcl`. Keep the scenario assumptions and valuation limitations explicit. |
| `05961c10c1ab9568920163f6af4663e1ea6a2ddf` | Regenerated valuation observations and local editor settings. | Discard the fixture replacement. It changes 138 recorded values without an accepted baseline decision. |
| `43406c05f57223331c8438675c46185d7d478586` | Windows upside metrics, regenerated observations, and valuation research. | Reject the metric patch. It passes upside into discount narratives and changes existing discount filters. Archive research and observations. |
| `41e168ea56373815cf673026a18bc06d84dc671e` | Windows portfolio import, regime, and board changes. | Discard the old combined draft. Current code contains the regime and board work. The old importer drops sub-dollar positions. |
| `637455d0255695337ac2fc5115355f18acc8e904` | Desktop S&P 500 count corrections. | Discard the duplicate. Current `main` already uses 501 entries and matching tests. |
| `06f4214e26fa42f05d1f162de82fefc624356d26` | Android parity shell, engines, local QA, and editor artifacts. | Reject the product expansion. It adds a second portfolio database, floating-point quantities, and direct investment actions. |
| `172e05160a787a72e153b46e184afdea390aa72b` | Flutter commands and old documentation. | Discard the stale draft. Current commands and guides own Flutter setup. |
| `8524d74d1cab8a89b1a9542348f199d1b334e576` | Android loading experiments and desktop WACC changes. | Reject the combined draft. The new market-cap fallback prefers averaged diluted shares over outstanding shares. |

### Review Details

The old Windows importer rejects positions below one dollar of book cost.
The current [import contract](../product/advisor-csv-import.md) explicitly preserves those positions.

Three regime files in stash `41e168ea56` exactly match current `main`.
Current `conditionalPlan.ts` also contains the shared actionable gates.
The supported importer now lives in `portfolioCsv`; the stashed replacement uses different rules.

The Windows metric draft mixes two denominators.
It changes `minGapPct` to use return from market price while current filters measure discount from target price.
It also passes that return into narratives that still say discount.
The review rejects this inconsistent patch. It does not certify every existing Windows label.

The parity draft stores portfolio quantities as SQLite `REAL` in `parity_state.db`.
Current Android positions use the shared import book and fixed-point quantities.
The draft also maps setup labels directly to Add, Trim, and Exit actions.
These changes conflict with the current product boundary.

The older desktop draft derives market capitalization through `latest_share_count`.
That function prefers `diluted_average_shares` before current `shares_outstanding`.
The review rejects that new use of an averaged denominator.
Later Android refresh ownership and persistence work supersedes the old combined loading experiment.

## Accepted Research

The [ORCL case](../../lab/orcl/README.md) stays separate from application valuation.
Its 13 existing tests passed during this review.
The review checked FY2026 cash-flow inputs and FY2027 Q1 balance-sheet inputs against the linked primary sources.
Scenario recovery, financing treatment, WACC, and terminal assumptions remain unverified.
The case supplies a reproducible calculation, not an accepted intrinsic value.

## Recovery

All rejected source changes remain recoverable outside the working repository.

Archive directory: `G:\dev\archives\discount_screener\closure-2026-09-26`.

| Artifact | Contents | SHA256 |
| --- | --- | --- |
| `reviewed-state.bundle` | Ten complete stash commits, their untracked parents, and the audit integration branch. | `a49d2162d61fab7f8c57b73d04e9fa20027f953b6910e1f51f5182542f2dd1f7` |
| `evidence-and-ignored.zip` | Review logs, test failures, source captures, and ignored audit workspace records. | `15e6f969aa8d0799e8961d66011e4121f895572f4c84a0acfbbc054fdee67830` |

`refs.json` maps each archived ref to its commit and description.
`git bundle verify` passed. An independent mirror clone passed `git fsck --full --no-reflogs`.
The ZIP integrity check read all 66 entries successfully.

To recover one stash in a clean recovery checkout:

```powershell
git fetch 'G:/dev/archives/discount_screener/closure-2026-09-26/reviewed-state.bundle' 'refs/archive/closure-2026-09-26/stash-00'
git stash branch recovered-audit FETCH_HEAD
```

Use `refs.json` to select another stash. Recovery can restore both tracked and untracked files.
The archive also contains an independent verified mirror at `verified.git`.

These archives exist only on this machine. They are not an off-machine backup.
