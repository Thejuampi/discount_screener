//! Build-time stamps for the Windows/Tauri app and `vantage-headless`.
//!
//! - `DS_APP_VERSION`: date-based version (same shape as `scripts/version.ps1`).
//!   Prefers the PowerShell script when available (`pwsh`, then `powershell`) so
//!   the desktop stamp stays bit-identical to Android's; otherwise replicates the
//!   script in Rust so Linux builds never need PowerShell.
//! - `DS_BUILD_SHA`: full 40-hex git SHA used by `vantage-headless --version`
//!   (and `/status`). Override with `VANTAGE_BUILD_SHA` when `.git` is absent
//!   (must be `[0-9a-f]{40}(-dirty)?` or `unknown`; anything else fails the
//!   build). Without git and without an override, falls back to `unknown`
//!   without failing. Appends `-dirty` when `git status --porcelain` is
//!   non-empty at build time.
//!
//! ## Cargo rerun limitations
//! `-dirty` can go stale: Cargo has no hook for "working tree dirtiness", only
//! for file paths / env vars. We deliberately do **not** list every tracked
//! file. A clean→dirty (or dirty→clean) flip without a commit therefore keeps
//! the previous `-dirty` suffix until something we do watch changes (HEAD,
//! the branch ref, `packed-refs`, `version.ps1`, or `VANTAGE_BUILD_SHA`).

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::OnceLock;

fn main() {
    println!("cargo:rerun-if-changed=../../../scripts/version.ps1");
    println!("cargo:rerun-if-env-changed=VANTAGE_BUILD_SHA");
    emit_git_reruns();

    println!("cargo:rustc-env=DS_APP_VERSION={}", app_version());
    println!("cargo:rustc-env=DS_BUILD_SHA={}", build_sha());

    #[cfg(feature = "gui")]
    tauri_build::build()
}

/// Watch git identity inputs so `DS_BUILD_SHA` / `DS_APP_VERSION` stay fresh.
///
/// Paths come from `git rev-parse --path-format=absolute --git-path …`, which
/// resolves refs into the **common** git dir. That matters for linked
/// worktrees: `--absolute-git-dir` is `.git/worktrees/<name>/`, and joining a
/// ref there yields a path that does not exist (Cargo then rebuilds every
/// time). `--git-path refs/heads/<branch>` points at the shared
/// `.git/refs/heads/<branch>` instead.
///
/// After `git pack-refs --all --prune` the loose ref file is gone (and for
/// nested names like `feat/foo`, so is `refs/heads/feat/`). Watching
/// `packed-refs` covers packs; watching the stable `refs/heads` directory
/// covers the loose ref coming back on the next commit (HEAD itself is often
/// unchanged: it still says `ref: refs/heads/…`).
/// **Only existing paths are emitted**: Cargo treats a missing
/// `rerun-if-changed` path as Dirty on every build (`the file … is missing`).
fn emit_git_reruns() {
    let Some(head) = git_stdout(&[
        "rev-parse",
        "--path-format=absolute",
        "--git-path",
        "HEAD",
    ]) else {
        // No .git: still watch the relative HEAD the old build.rs watched, in
        // case a shallow copy appears later in the same build tree.
        emit_rerun_if_exists(Path::new("../../../.git/HEAD"));
        return;
    };
    emit_rerun_if_exists(Path::new(&head));

    if let Ok(contents) = std::fs::read_to_string(&head) {
        if let Some(r) = contents.strip_prefix("ref: ") {
            let r = r.trim();
            if !r.is_empty() {
                if let Some(ref_path) =
                    git_stdout(&["rev-parse", "--path-format=absolute", "--git-path", r])
                {
                    // Loose ref when present. After `pack-refs --prune` it is
                    // gone, and for nested branch names (`feat/foo`) the
                    // intermediate dir (`refs/heads/feat`) is gone too. Always
                    // watch the stable `refs/heads` directory (survives pack)
                    // so recreating a loose ref dirties the build. Never emit a
                    // missing path: Cargo treats those as Dirty on every build.
                    let ref_path = PathBuf::from(ref_path);
                    emit_rerun_if_exists(&ref_path);
                    if r.starts_with("refs/heads/") {
                        if let Some(heads) = git_stdout(&[
                            "rev-parse",
                            "--path-format=absolute",
                            "--git-path",
                            "refs/heads",
                        ]) {
                            emit_rerun_if_exists(Path::new(&heads));
                        }
                    }
                }
            }
        }
    }

    if let Some(packed) =
        git_stdout(&["rev-parse", "--path-format=absolute", "--git-path", "packed-refs"])
    {
        emit_rerun_if_exists(Path::new(&packed));
    }
}

fn emit_rerun_if_exists(path: &Path) {
    if path.exists() {
        println!("cargo:rerun-if-changed={}", path.display());
    }
}

fn app_version() -> String {
    if let Some(v) = try_powershell_version() {
        return v;
    }
    rust_version_from_git().unwrap_or_else(|| "0.0.0-unknown".to_string())
}

fn try_powershell_version() -> Option<String> {
    // Prefer `pwsh` (PowerShell 7+) then Windows PowerShell 5.x `powershell`.
    for shell in ["pwsh", "powershell"] {
        if let Some(v) = try_ps_script(shell) {
            return Some(v);
        }
    }
    None
}

fn try_ps_script(shell: &str) -> Option<String> {
    let output = Command::new(shell)
        .args([
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            "../../../scripts/version.ps1",
        ])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let line = String::from_utf8_lossy(&output.stdout)
        .lines()
        .next()?
        .trim()
        .to_string();
    if line.is_empty() {
        None
    } else {
        Some(line)
    }
}

/// Rust port of `scripts/version.ps1` line 1 (the version string). Line 2
/// (Android versionCode) is unused by this crate.
fn rust_version_from_git() -> Option<String> {
    let stamp = git_stdout(&["log", "-1", "--format=%cd", "--date=format:%Y.%m.%d.%H%M"])?;
    let mut branch = git_stdout(&["rev-parse", "--abbrev-ref", "HEAD"])?;
    if branch == "HEAD" {
        // Detached HEAD (e.g. CI checked out a tag/commit): treat as a feature build.
        branch = "detached".to_string();
    }
    let short = git_stdout(&["rev-parse", "--short", "HEAD"])?;
    let dirty_suffix = if git_is_dirty() { "-dirty" } else { "" };
    let is_release = branch == "main" || branch == "master";
    if is_release {
        Some(format!("{stamp}.{short}{dirty_suffix}"))
    } else {
        // version.ps1 uses ToLowerInvariant; Rust's to_lowercase is Unicode-aware
        // (e.g. Turkish İ). Branch names we care about are ASCII, and the sanitize
        // below keeps only [a-z0-9-], so the results are identical for those.
        let safe: String = branch
            .to_lowercase()
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '-' {
                    c
                } else {
                    '-'
                }
            })
            .collect();
        Some(format!("{stamp}.{safe}.{short}{dirty_suffix}"))
    }
}

fn build_sha() -> String {
    if let Ok(v) = std::env::var("VANTAGE_BUILD_SHA") {
        let v = v.trim();
        if !v.is_empty() {
            // Fail the build on a bad override: the caller asked for a specific
            // identity, so silently stamping `unknown` would hide a misconfig
            // in a release artifact. `unknown` itself is an allowed explicit value.
            if !is_valid_build_sha(v) {
                panic!(
                    "invalid VANTAGE_BUILD_SHA={v:?}: expected [0-9a-f]{{40}}(-dirty)? or \"unknown\""
                );
            }
            return v.to_string();
        }
    }
    match git_stdout(&["rev-parse", "HEAD"]) {
        Some(sha) if !sha.is_empty() => {
            if git_is_dirty() {
                format!("{sha}-dirty")
            } else {
                sha
            }
        }
        _ => "unknown".to_string(),
    }
}

fn is_valid_build_sha(v: &str) -> bool {
    if v == "unknown" {
        return true;
    }
    let sha = v.strip_suffix("-dirty").unwrap_or(v);
    sha.len() == 40 && sha.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

fn git_is_dirty() -> bool {
    // Single porcelain call shared by DS_APP_VERSION and DS_BUILD_SHA.
    static DIRTY: OnceLock<bool> = OnceLock::new();
    *DIRTY.get_or_init(|| match Command::new("git")
        .args(["status", "--porcelain"])
        .current_dir(repo_root())
        .output()
    {
        Ok(o) if o.status.success() => !String::from_utf8_lossy(&o.stdout).trim().is_empty(),
        _ => false,
    })
}

fn git_stdout(args: &[&str]) -> Option<String> {
    let output = Command::new("git")
        .args(args)
        .current_dir(repo_root())
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let s = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

fn repo_root() -> PathBuf {
    // build.rs runs with CWD = package dir (apps/windows/src-tauri).
    Path::new("../../..").to_path_buf()
}
