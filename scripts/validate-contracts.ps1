$ErrorActionPreference = "Stop"

Push-Location "$PSScriptRoot/../apps/desktop"
try {
    cargo test contract_fixture
} finally {
    Pop-Location
}

Push-Location "$PSScriptRoot/../apps/android"
try {
    ./gradlew :core:test --tests com.discountscreener.core.contracts.ContractFixtureTest
} finally {
    Pop-Location
}
