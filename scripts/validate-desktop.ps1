$ErrorActionPreference = "Stop"

Push-Location "$PSScriptRoot/../apps/desktop"
try {
    cargo fmt --check
    cargo test
    cargo run -- --smoke
} finally {
    Pop-Location
}
