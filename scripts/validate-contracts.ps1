$ErrorActionPreference = "Stop"

# Generated policy first: it is the cheapest check, and until now nothing ran it
# at all. Both generated files had silently drifted from the contract they claim
# to be generated from, which no amount of testing the drifted output can catch.
& "$PSScriptRoot/generate-sec-driver-normalization-policy.ps1" -Check

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
