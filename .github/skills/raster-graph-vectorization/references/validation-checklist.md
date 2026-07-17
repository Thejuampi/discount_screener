# Validation Checklist

Validation is mandatory before reporting success.

Do not report success until these checks pass or the unresolved figures are explicitly marked `needs-human-review`.

## Hard Acceptance Criteria

- No raster graph references.
- No `.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.bmp`, or `.tiff` graph references in the final primary Markdown.
- No raster-backed SVGs.
- No SVG containing `<image>`, `data:image`, base64 image payloads, or references to raster files.
- No auto-traced SVGs that visually behave like low-resolution raster images.
- No jagged traced text.
- No traced axis labels, traced tick labels, or traced grid lines.
- No traced curve blobs where semantic paths or digitized series should exist.
- Quantitative charts reconstructed as semantic standalone SVGs.
- Existing vector figures extracted 1:1 where possible.
- Equations converted to proper Markdown or KaTeX math.
- Tables converted to Markdown tables where practical.
- Diagrams converted to semantic SVG or Mermaid as appropriate.
- Every SVG contains `<title>`, `<desc>`, real SVG primitives, and no raster payload.
- Every Markdown figure reference has meaningful alt text.
- Important figures have adjacent figure notes.

## Markdown Validation

The final Markdown must pass these checks:

- no `.png` graph references
- no `.jpg` graph references
- no `.jpeg` graph references
- no `.webp` graph references
- no `.gif` graph references
- no raster-backup graph references
- no `data:image`
- all graph references point to `.svg`
- all referenced SVG files exist
- all important graphs have figure notes

## SVG Integrity Validation

Every SVG must pass these checks:

- no `<image>`
- no `data:image`
- no base64 raster payload
- no raster file references
- contains `<title>`
- contains `<desc>`
- contains `viewBox`
- contains semantic primitives
- has accessible labeling where appropriate

## Anti-Tracing Validation

A final SVG fails if any of these are true:

- text is outlines instead of `<text>`
- axis labels are traced paths
- grid lines are jagged traced paths
- the curve is made of noisy pixel contours
- the SVG is visually blurry or low-resolution
- the SVG file is mostly thousands of tiny path fragments
- the SVG cannot be understood structurally

If any of these are true, rebuild semantically.

## Calibration Validation

For each quantitative graph, report:

- source dimensions
- SVG `viewBox`
- plot rectangle
- x-axis domain
- y-axis domain
- tick values
- tick pixel positions
- coordinate transform
- data reconstruction method

## Visual Fidelity Validation

Render each SVG to PNG and compare it against the source reference.

Check:

- axis alignment
- tick alignment
- grid alignment
- curve alignment
- point positions
- bar heights
- boundary positions
- legend placement
- label placement
- aspect ratio
- overall visual match

Recommended tolerances:

```text
axis or tick placement error <= 2 px
major data mark error <= 3 px
curve peak or trough error <= 3 px
label placement visually equivalent
colors and strokes visually equivalent
```

If the figure cannot meet tolerance, mark it `needs-human-review`. Do not restore the raster.

## Formula Validation

Search for OCR artifacts and fix all hits before completion:

```text
V ar
C o v
R S S
S E(
X[T]
_[...]
[≈]
[−]
[ˆ]
[β]
[σ]
[µ]
[∞]
�
control characters
```

## Output Structure

Use this folder layout:

```text
docs/
  MIT-Quant-Bible.md
  MIT-Quant-Bible.native.md
  MIT-Quant-Bible.raster-backup.md
  MIT-Quant-Bible.raster-backup.assets/
    original extracted raster files only
  MIT-Quant-Bible.vectorized.assets/
    figures.manifest.json
    replacement_snippets.md
    conversion_report.md
    svg/
      fig-xxxx.svg
    data/
      fig-xxxx.points.csv
      fig-xxxx.calibration.json
    validation/
      fig-xxxx-rendered-preview.png
      fig-xxxx-overlay.png
      fig-xxxx-report.json
```

The primary Markdown may reference only `MIT-Quant-Bible.vectorized.assets/svg/*.svg` for figure assets.
