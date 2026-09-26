# Unmerged Change Review, 2026-09-26

This review closed the local branch backlog. No historical branch below carries a delivery promise.

| Branch | Decision | Evidence |
| --- | --- | --- |
| `android/profile-switch-and-refresh-fixes` | Delete local ref. | All 11 commits match changes already on `main`. |
| `codex/android-replay-volume-controls` | Delete local ref. | Both commits match changes already on `main`. |
| `merge-final-check` | Delete local ref. | It has no unique nonmerge patch. |
| `merge-all-final` | Delete local ref. | Merged PRs #24 and #25 supersede its TipRanks code. |
| `merge-all-verify` | Delete local ref. | Later integration supersedes its FMP and TipRanks code. |
| `e2e/valuation-pit-contract-artifacts` | Reject as product code. Archive its stopped QA record. | Its README forbids a code merge. The round has no verdict. |
| `lab/valuation-python` | Reject as product code. Archive its research record. | It records a failed goal and reproducible analysis. |
| `r10` | Reject as product code. Archive its stopped valuation trial. | Its QA round stopped before acceptance. |
| `roic-on` | Reject as product code. Archive its research candidate. | The comparison harness did not run. |
| `codex/adsk-valuation-integrity-audit` | Track the open repair in [issue #61](https://github.com/Thejuampi/discount_screener/issues/61). | Tests pass, but review found production blockers. |

## Recovery Records

The rejected historical branches and the audit base commit have a complete local Git bundle.

- Bundle: `G:\dev\archives\discount_screener\unmerged-review-2026-09-26.bundle`
- Bundle SHA256: `168E354FCDF9D48D92B1E20BB3D9BE9E04CA8AFB646E4E4698E9D0F0656E0F33`
- Audit worktree snapshot: `G:\dev\archives\discount_screener\audit-worktree-2026-09-26.zip`
- Snapshot SHA256: `45758C62E7F64F680D18694DC76B9CDD426DDA3A8700C2B4979F9CFA736FE51B`

`git bundle verify` passed. The snapshot ZIP opened and all 14 files decompressed.

The bundle contains these branch tips:

| Branch | Commit |
| --- | --- |
| `e2e/valuation-pit-contract-artifacts` | `82804f7508a2a2672295ee8d0ae940b27c2ab04d` |
| `lab/valuation-python` | `7a3a5188da4393fb833e770e3cdbe13af589e693` |
| `r10` | `b130b8c8bd754f8c4e94faf844b2e7101f769eab` |
| `roic-on` | `91a5112c6b4e119e3090aab7fffe128b081a0cf6` |
| `codex/adsk-valuation-integrity-audit` | `96cf76558ba9036d5916eb184f301874bd2c3822` |

Fetch a branch from the bundle when its historical evidence is needed.

```powershell
git fetch 'G:\dev\archives\discount_screener\unmerged-review-2026-09-26.bundle' 'refs/heads/lab/valuation-python:refs/heads/recovered/lab-valuation-python'
```

The ZIP contains staged and unstaged patches, nine untracked files, status, and restore instructions.

These archives exist only on this machine. They are not an off-machine backup.
