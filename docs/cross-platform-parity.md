# Cross-Platform Parity

Discount Screener has two user-facing clients: the Rust desktop app and the Android app.

## Default Rule

User-visible functionality should be kept in 1:1 parity across both clients by default.

- If a feature is added to Android, add the equivalent feature to the Rust desktop app.
- If a feature is added to the Rust desktop app, add the equivalent feature to Android.
- Differences in UI styling or implementation detail are fine. Differences in product capability are not the default.

## Exceptions

- **Android Plans tab (Dip hunter + leftover review, v1)** — Android-only. Windows keeps Dashboard 2.0 Act / Scale / Wait. Specs: [`../_bmad-output/implementation-artifacts/dip-board-spec-v1.md`](../_bmad-output/implementation-artifacts/dip-board-spec-v1.md), [`../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md`](../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md).

- **SEC companyfacts read (field set, not capability)** — Both clients now sieve the 4 MB body on
  the stream. The field sets differ, and they must. Android keeps `fp` and cuts everything that is
  not an annual consolidated 10-K row. The desktop keeps `frame`, `fy` and `accn`, and keeps
  quarters and `10-Q`/`8-K` rows, because `annual_candidates_with_shape`,
  `extract_normalized_investment_evidence` and `extract_current_shares` read them. Port one field
  set to the other client and the shares count and the investment evidence go wrong, silently.
  Each side has its own tests: `SecCompanyFactsSieveParityTest` on Android,
  `edgar::sieve_parity_tests` on the desktop.

One-platform changes are allowed only when the request explicitly says so or when the platform cannot support the behavior.

- Call out the exception clearly in the task or pull request.
- Update the relevant docs so the exception is obvious to future editors.
- Keep shared behavior in shared contracts or other platform-neutral code when practical.

## Review Check

Before finishing a feature, verify that:

- both clients expose the same user-visible behavior, or
- the scope is explicitly documented as Android-only or desktop-only.
