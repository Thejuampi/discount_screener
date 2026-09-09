# Wave B: dashboard header

Read SPEC.md, every companion, and .memlog.md first. Implement wave B only.
The parent owns design and architecture. Report a gap before changing this contract.
You are not alone in the codebase. Preserve all existing edits.

## Ownership

- New `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/ReturningDashboardHeader.kt`.
- The header and content wrapper in `DashboardScreen.kt`. Leave its current PositionsContent body for wave C.
- Optional focus callback in `TickerSearchBar.kt`.
- Focused pure-state and mounted dashboard header tests under the matching app test path.

Use the architecture's measured group, consumed user scroll, eight-dp threshold, and complete upward recovery.
The header includes title, actions, search, tabs, and header status strips.
Search suggestions and horizontal tabs must sit outside the content scroll connection.
Keep a fully hidden header out of accessibility traversal. Do not destroy the search state or focus.
Keep measured child height stable while clipped and reclaim the hidden height in the content layout.
Reset on tab selection and dashboard composition after Detail return. Pin search focus, active search, and dialogs.
Use the platform accessibility manager for touch exploration; preserve listener cleanup.
Short content cannot hide. A boundary gesture recovers a hidden group after content becomes short.
Do not attach to DetailScreen or start provider work.

## Evidence

Write failing tests first, then implement. Use Robolectric Compose tests, with no emulator or network.
Map every header Case in examples.md to a named test. Mounted tests must use the real DashboardScreen.
Tests must check header bounds and list position, not only a Boolean state helper.
Use existing local test infrastructure. Run focused tests with `--rerun` immediately after the test task.
Do not commit, push, or spawn agents.
Write `wave-b-result.md` with file changes, red/green evidence, test mapping, and any unresolved gap.
