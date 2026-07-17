# Conversion Report Template

Write the final report to `docs/MIT-Quant-Bible.vectorized.assets/conversion_report.md`.

Use this exact format:

```md
# Raster-to-Vector Conversion Report

## Inventory

- Total visual assets found:
- Already-vector PDF figures:
- Embedded raster figures:
- Mixed figures:
- Formula assets:
- Table or algorithm assets:
- Quantitative graph assets:
- Converted successfully:
- Needs human review:

## Converted Figures

| Figure ID | Page | Source kind | Classification | Strategy | Output | Status |
| --- | ---: | --- | --- | --- | --- | --- |

## Validation

| Check | Result |
| --- | --- |
| No raster refs in primary Markdown | pass/fail |
| No raster refs inside SVGs | pass/fail |
| No `<image>` inside SVGs | pass/fail |
| No `data:image` payloads | pass/fail |
| No traced or raster-looking SVGs | pass/fail |
| All SVG files exist | pass/fail |
| All SVGs have `<title>` | pass/fail |
| All SVGs have `<desc>` | pass/fail |
| All important graphs have figure notes | pass/fail |
| Axis calibration recorded | pass/fail |
| Visual overlay checked | pass/fail |
| Formula OCR artifacts checked | pass/fail |

## Files Changed

List exact files changed.

## Human Review Required

List any figure that could not be reconstructed within tolerance.

## Notes

Mention unresolved fidelity risks.
```

Do not claim success unless all validation checks pass or unresolved figures are explicitly marked `needs-human-review`.
