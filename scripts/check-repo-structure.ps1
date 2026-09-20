$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

Push-Location $repoRoot
try {
    $paths = @(git ls-files --cached --others --exclude-standard | Where-Object {
        Test-Path -LiteralPath $_ -PathType Leaf
    })
    if ($LASTEXITCODE -ne 0) {
        throw "git ls-files failed"
    }

    $forbiddenTrackedPrefixes = @(
        ".agents/skills/",
        ".grok/",
        "_bmad/",
        "_bmad-output/",
        "mcps/",
        "plans/"
    )

    foreach ($path in $paths) {
        foreach ($prefix in $forbiddenTrackedPrefixes) {
            if ($path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                $failures.Add("Tracked local-tool path: $path")
            }
        }
    }

    $forbiddenLocalPaths = @(
        ".agents/skills",
        ".grok",
        "_bmad",
        "_bmad-output",
        "mcps",
        "plans"
    )

    foreach ($path in $forbiddenLocalPaths) {
        if (Test-Path -LiteralPath $path) {
            $failures.Add("Local repository state must not exist: $path")
        }
    }

    $requiredFiles = @(
        "AGENTS.md",
        "apps/android/AGENTS.md",
        "apps/desktop/AGENTS.md",
        "apps/flutter/AGENTS.md",
        "apps/windows/AGENTS.md",
        "docs/index.md"
    )

    foreach ($path in $requiredFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $failures.Add("Required repository guide is missing: $path")
        }
    }

    $markdownPaths = @($paths | Where-Object {
        $_.EndsWith(".md", [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $_.StartsWith("docs/archive/", [System.StringComparison]::OrdinalIgnoreCase) -and
        (
            $_ -in @("AGENTS.md", "CLAUDE.md", "README.md") -or
            $_.StartsWith("apps/", [System.StringComparison]::OrdinalIgnoreCase) -or
            $_.StartsWith("docs/", [System.StringComparison]::OrdinalIgnoreCase) -or
            $_.StartsWith("shared/", [System.StringComparison]::OrdinalIgnoreCase)
        )
    })

    $linkCount = 0
    foreach ($path in $markdownPaths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            continue
        }

        $file = Get-Item -LiteralPath $path
        $content = Get-Content -Raw -LiteralPath $file.FullName

        if ($content -match '\]\([^)]*(?:_bmad-output|\.agents/skills|\.grok/rules/bmad)') {
            $failures.Add("Active document links to local BMad state: $path")
        }

        foreach ($match in [regex]::Matches($content, '\]\(([^)]+)\)')) {
            $target = $match.Groups[1].Value.Trim('<', '>')
            if ($target -eq "" -or $target -match '^(https?://|mailto:|#|codex:)') {
                continue
            }

            $cleanTarget = [System.Uri]::UnescapeDataString(($target -split '#')[0])
            if ($cleanTarget -eq "") {
                continue
            }

            $linkCount++
            $candidate = Join-Path $file.DirectoryName $cleanTarget
            if (-not (Test-Path -LiteralPath $candidate)) {
                $failures.Add("Broken local link in ${path}: $target")
            }
        }
    }

    $jsonPaths = @($paths | Where-Object {
        $_.StartsWith("shared/contracts/", [System.StringComparison]::OrdinalIgnoreCase) -and
        $_.EndsWith(".json", [System.StringComparison]::OrdinalIgnoreCase)
    })

    foreach ($path in $jsonPaths) {
        try {
            Get-Content -Raw -LiteralPath $path | ConvertFrom-Json | Out-Null
        } catch {
            $failures.Add("Invalid contract JSON: $path")
        }
    }

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ }
        exit 1
    }

    Write-Host "Repository structure OK: $($markdownPaths.Count) active Markdown files, $linkCount local links, $($jsonPaths.Count) contract JSON files."
} finally {
    Pop-Location
}
