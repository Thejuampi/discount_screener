//! Build-time stamps for the Windows/Tauri app and `vantage-headless`.
//!
//! - `DS_APP_VERSION`: date-based version (same shape as `scripts/version.ps1`).
//!   Prefers the PowerShell script when available (Windows / CI with pwsh) so the
//!   desktop stamp stays bit-identical to Android's; otherwise replicates the
//!   script in Rust so Linux builds never need PowerShell.
//! - `DS_BUILD_SHA`: full 40-hex git SHA used by `vantage-headless --version`
//!   (and `/status`). Override with `VANTAGE_BUILD_SHA` when `.git` is absent;
//!   falls back to `unknown` without failing the build. Appends `-dirty` when
//!   the working tree has uncommitted changes (override values are used as-is).

use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=../../../scripts/version.ps1");
    println!("cargo:rerun-if-env-changed=VANTAGE_BUILD_SHA");
    emit_git_reruns();

    println!("cargo:rustc-env=DS_APP_VERSION={}", app_version());
    println!("cargo:rustc-env=DS_BUILD_SHA={}", build_sha());

    #[cfg(feature = "gui")]
    tauri_build::build()
}

/// Watch `.git/HEAD` and the ref it points at (resolved via `git`, so worktrees
/// and bare checkouts both work). Absolute paths so Cargo can find them from
/// any package cwd.
fn emit_git_reruns() {
    let Some(git_dir) = git_stdout(&["rev-parse", "--absolute-git-dir"]) else {
        // No .git: still watch the relative HEAD the old build.rs watched, in
        // case a shallow copy appears later in the same build tree.
        println!("cargo:rerun-if-changed=../../../.git/HEAD");
        return;
    };
    let git_dir = PathBuf::from(git_dir);
    let head = git_dir.join("HEAD");
    println!("cargo:rerun-if-changed={}", head.display());
    if let Ok(contents) = std::fs::read_to_string(&head) {
        if let Some(r) = contents.strip_prefix("ref: ") {
            let r = r.trim();
            if !r.is_empty() {
                println!("cargo:rerun-if-changed={}", git_dir.join(r).display());
            }
        }
    }
}

fn app_version() -> String {
    if let Some(v) = try_powershell_version() {
        return v;
    }
    rust_version_from_git().unwrap_or_else(|| "0.0.0-unknown".to_string())
}

fn try_powershell_version() -> Option<String> {
    let output = Command::new("powershell")
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

fn git_is_dirty() -> bool {
    match Command::new("git")
        .args(["status", "--porcelain"])
        .current_dir(repo_root())
        .output()
    {
        Ok(o) if o.status.success() => !String::from_utf8_lossy(&o.stdout).trim().is_empty(),
        _ => false,
    }
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
