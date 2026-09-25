# Cross-Platform Parity

## Android Positions UX

Android shows stock exposure, research labels, local position facts, and a dashboard header that returns on upward scroll.
Windows and desktop keep their existing presentation. No import, valuation, or score policy changes accompany this Android view.
Partial stock totals suppress all weights. P/L uses paired value and cost coverage.
See [Android Positions](../product/android-positions.md) for the field and interaction contract.

Discount Screener has four clients: Rust desktop, Windows Tauri, native Android, and Flutter.

## Default Rule

Shared financial semantics must stay in parity across clients.

- Use `shared/contracts` for shared ranking and valuation behavior.
- Check all four clients when a shared contract changes.
- Keep UI styling and platform integration local.
- Document each product-capability difference as an exception.

## Exceptions

- **Android Plans tab** — Android-only. Windows keeps Dashboard 2.0 Act / Scale / Wait. See [Dip](../product/android-plans-dip.md), [Cross](../product/android-plans-cross.md), and [Leftover](../product/android-plans-leftover.md).

- **Android Earnings tab** — Android-only. See [the current earnings behavior](../product/earnings-gate.md).

- **Android Chase book** — Android-only paint of Held, pin, and Positions. See [Advisor CSV import](../product/advisor-csv-import.md).

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

One-platform changes require explicit scope or a documented platform constraint.

- Call out the exception clearly in the task or pull request.
- Update the relevant docs so the exception is obvious to future editors.
- Keep shared behavior in shared contracts or other platform-neutral code when practical.

## Review Check

Before finishing a feature, check that:

- shared contracts produce the same meaning on each participating client, or
- the document names the platform exception.
