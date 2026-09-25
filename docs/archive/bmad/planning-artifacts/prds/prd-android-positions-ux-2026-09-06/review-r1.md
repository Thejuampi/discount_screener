# PRD review round 1

Admission: cold_start_waived. Named agents failed because their saved model identifiers were empty.
Default agents used the saved Sensei and Advisor instructions. No role configuration changed.

Sensei approved after three anticipatory passes. Advisor returned revise.

| ID | Finding | Resolution |
| --- | --- | --- |
| A1 | Partial P/L could subtract complete-book cost | FR-4a pairs value and cost eligibility and separates P/L coverage |
| A2 | Review increase could imply size advice | Use Review opportunity with an explicit research meaning and evidence gate |
| A3 | Documentation deliverables were absent | Add the exact documentation deliverables |
| S1 | Value and cost coverage differ | FR-4a defines separate coverage |
| S2 | Zero denominator could produce weights | Spec must suppress weights and P/L percent for zero denominators |
| S3 | Header motion could move the selected row | Spec must preserve list state and ignore layout-generated motion |
| S4 | Sort ties need deterministic order | Spec must define ties and missing-value placement |
| S5 | Return paths need UI tests | Spec must map mounted interaction tests |

Regression rows: Backend refuse, UI mute dash; Signal written by the engine, read by nothing;
Calendar key presence means fresh evidence; Import removes small positive positions;
Profile switch starts deferred market work early; Presenter tests cover dead code;
`--rerun` written once for two tasks.

Device QA remains Not run until Juan requests it.
