$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$canonical = Join-Path $root ".agents\skills"
if (-not (Test-Path -LiteralPath $canonical)) {
    throw "Missing canonical catalog: $canonical"
}

$canonicalNames = @(
    Get-ChildItem -Directory $canonical |
        Where-Object { Test-Path (Join-Path $_.FullName "SKILL.md") } |
        Select-Object -ExpandProperty Name |
        Sort-Object
)
if ($canonicalNames.Count -eq 0) {
    throw "Canonical catalog has no SKILL.md folders: $canonical"
}

$mirrors = @(
    ".grok\skills",
    ".claude\skills",
    ".codex\skills",
    ".cursor\skills"
)

$failed = $false
foreach ($rel in $mirrors) {
    $path = Join-Path $root $rel
    if (-not (Test-Path -LiteralPath $path)) {
        continue
    }
    $hits = @(
        Get-ChildItem -Directory $path -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "SKILL.md") }
    )
    if ($hits.Count -gt 0) {
        $failed = $true
        Write-Host "Duplicate skill root: $rel ($($hits.Count) SKILL.md folders). Grok or Codex would load twice."
    }
}

$github = Join-Path $root ".github\skills"
if (Test-Path -LiteralPath $github) {
    $overlap = @(
        Get-ChildItem -Directory $github -ErrorAction SilentlyContinue |
            Where-Object {
                (Test-Path (Join-Path $_.FullName "SKILL.md")) -and
                ($canonicalNames -contains $_.Name)
            } |
            Select-Object -ExpandProperty Name
    )
    if ($overlap.Count -gt 0) {
        $failed = $true
        Write-Host "Name clash with .github/skills: $($overlap -join ', ')"
    }
}

if ($failed) {
    throw "Skill catalog has a second copy. Edit .agents/skills only. Delete the mirror."
}

Write-Host "Canonical catalog: $($canonicalNames.Count) skills in .agents/skills. No Grok or Codex mirror."
