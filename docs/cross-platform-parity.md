# Cross-Platform Parity

Discount Screener has two user-facing clients: the Rust desktop app and the Android app.

## Default Rule

User-visible functionality should be kept in 1:1 parity across both clients by default.

- If a feature is added to Android, add the equivalent feature to the Rust desktop app.
- If a feature is added to the Rust desktop app, add the equivalent feature to Android.
- Differences in UI styling or implementation detail are fine. Differences in product capability are not the default.

## Exceptions

- **Android Plans tab (Dip hunter + leftover review, v1)** — Android-only. Windows keeps Dashboard 2.0 Act / Scale / Wait. Specs: [`../_bmad-output/implementation-artifacts/dip-board-spec-v1.md`](../_bmad-output/implementation-artifacts/dip-board-spec-v1.md), [`../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md`](../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md).

- **SEC companyfacts read (cost, not capability)** — Android sieves the 4 MB body on the stream and
  keeps about 3% of it (`SecCompanyFactsSieve`). Windows still loads the whole file into a
  `serde_json::Value` in `fetch_company_facts` (`apps/windows/src-tauri/src/edgar.rs`), across about
  ten call sites. Both clients read the same facts and reach the same drivers, so this is a resource
  gap, not a product gap. A desktop has the RAM; a phone does not. Close it on the desktop when the
  file count per session grows.

One-platform changes are allowed only when the request explicitly says so or when the platform cannot support the behavior.

- Call out the exception clearly in the task or pull request.
- Update the relevant docs so the exception is obvious to future editors.
- Keep shared behavior in shared contracts or other platform-neutral code when practical.

## Review Check

Before finishing a feature, verify that:

- both clients expose the same user-visible behavior, or
- the scope is explicitly documented as Android-only or desktop-only.
