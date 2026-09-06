# Cross-Platform Parity

Discount Screener has three user-facing clients: the Rust desktop app, the Windows Tauri app, and the Android app.

## Default Rule

User-visible functionality should be kept in 1:1 parity across clients by default.

- If a feature is added to Android, add the equivalent feature to the Rust desktop app.
- If a feature is added to the Rust desktop app, add the equivalent feature to Android.
- Differences in UI styling or implementation detail are fine. Differences in product capability are not the default.

## Exceptions

- **Android Plans tab (Dip hunter + leftover review, v1)** — Android-only. Windows keeps Dashboard 2.0 Act / Scale / Wait. Specs: [`../_bmad-output/implementation-artifacts/dip-board-spec-v1.md`](../_bmad-output/implementation-artifacts/dip-board-spec-v1.md), [`../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md`](../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md).

- **Android Earnings tab (pre-earnings risk gate)** — Android-only. Cell is implied-move vs own median |AR|. SUE slope is a card diagnostic. Revenue trail can cut a Hold. PRD: [`../_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md`](../_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md).

- **Android Chase book (Import book)** — Android-only paint of Held, pin, and the Positions tab. Parse/merge goldens are shared in `advisor-csv-import-v1.yaml` `/3`. Windows Advisor UI stays. Desktop has no import. Android persists lots in SQLite and refuses ledger apply. Positions closeness is a Core enum on the New York session day. Spec: [`../_bmad-output/specs/spec-android-chase-portfolio/SPEC.md`](../_bmad-output/specs/spec-android-chase-portfolio/SPEC.md).

- **SEC companyfacts read (field set, not capability)** — Both clients now sieve the 4 MB body on
  the stream. The field sets differ, and they must. Android keeps `fp` and cuts everything that is
  not an annual consolidated 10-K row. The desktop keeps `frame`, `fy` and `accn`, and keeps
  quarters and `10-Q`/`8-K` rows, because `annual_candidates_with_shape`,
  `extract_normalized_investment_evidence` and `extract_current_shares` read them. Port one field
  set to the other client and the shares count and the investment evidence go wrong, silently.
  Each side has its own tests: `SecCompanyFactsSieveParityTest` on Android,
  `edgar::sieve_parity_tests` on the desktop.

  Both clients also read the document once per issuer. Android caches the sieved copy on disk for a
  day; the desktop holds it in memory for six hours, capped at 64 issuers
  (`edgar::shared_company_facts`). Before that, a screen pulled the same 4 MB twice for every
  issuer: once for the shares count, once for the driver history.

One-platform changes are allowed only when the request explicitly says so or when the platform cannot support the behavior.

- Call out the exception clearly in the task or pull request.
- Update the relevant docs so the exception is obvious to future editors.
- Keep shared behavior in shared contracts or other platform-neutral code when practical.

## Review Check

Before finishing a feature, verify that:

- both clients expose the same user-visible behavior, or
- the scope is explicitly documented as Android-only or desktop-only.
