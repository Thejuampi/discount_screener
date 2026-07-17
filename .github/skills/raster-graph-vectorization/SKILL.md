---
name: raster-graph-vectorization
description: 'Convert graphs, charts, plots, and PDF figures in docs/MIT-Quant-Bible.* into high-fidelity native vector or semantic Markdown assets. Use when extracting already-vector PDF figures to SVG, semantically reconstructing raster graphs without tracing, and enforcing no-raster and no-auto-trace delivery rules.'
argument-hint: 'Describe the MIT Quant Bible figure set, page range, or vectorization slice'
user-invocable: true
---

# High-Fidelity Raster Graph and PDF Figure Vectorization

## Purpose

Convert every graph, chart, plot, statistical figure, and data visualization in the MIT Quant Bible asset pipeline into high-fidelity, Markdown-embeddable vector or native assets.

The final Markdown must render figures from real vector or native content, not raster images and not raster-looking traced SVG blobs.

The goal is that both humans and downstream agents can understand the figure contents from crisp SVG geometry, real text labels, semantic captions, alt text, SVG `<title>` and `<desc>`, optional machine-readable metadata, and adjacent Markdown figure notes.

The source raster may be used only as a visual reference. It is never an acceptable final rendering format.

## Core Principle

There are only two valid conversion paths.

1. Already-vector PDF figure.
   Extract the vector content 1:1 from PDF drawing operators into standalone SVG.
   Preserve paths, lines, text, geometry, colors, and layout.
   Do not rasterize and re-trace.

2. Raster graph or raster figure.
   Reconstruct the figure semantically.
   Rebuild axes, ticks, labels, grid, legends, curves, points, shapes, and annotations as real SVG primitives.
   Do not use naive image tracing.

If neither path can produce a high-quality vector result, mark the figure `needs-human-review` and do not fall back to raster.

## Non-Negotiable Acceptance Criteria

- No raster graph references in the final primary Markdown.
- No `.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.bmp`, or `.tiff` graph references.
- No raster-backed SVGs.
- No SVG containing `<image>`, `data:image`, base64 image payloads, or references to raster files.
- No auto-traced SVGs that behave like low-resolution raster images.
- No jagged traced text, traced axis labels, traced tick labels, traced grid lines, or traced curve blobs.
- Quantitative charts must be semantic standalone SVGs.
- Existing vector figures in the PDF must be extracted 1:1 where possible.
- Equations must be proper Markdown or KaTeX math.
- Tables must be Markdown tables where possible.
- Diagrams must be semantic SVG or Mermaid, depending on geometry requirements.
- Each SVG must contain `<title>`, `<desc>`, real SVG primitives, and no raster payload.
- Each Markdown figure reference must have meaningful alt text.
- Important figures must have adjacent figure notes.

When fidelity and no-raster conflict, no raster wins. If fidelity is not good enough, do not cheat by restoring the raster. Mark the figure `needs-human-review`.

## Hard Anti-Tracing Rule

Naive bitmap tracing is not an acceptable final result.

A figure fails if it is mainly thousands of irregular tiny paths from raster pixels, if text is jagged outline blobs, if grid lines are wavy or pixel-stepped, if curves are stair-stepped or fuzzy, if axes are traced image fragments, if the SVG looks low-resolution when zoomed, or if the SVG has no meaningful chart structure.

A traced SVG may be used only as a temporary reference layer during reconstruction. It must not be shipped as the final asset.

## Required Output Structure

Use this output layout:

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
      fig-0006-conditional-probability.svg
      fig-0007-bayes-base-rate.svg
      fig-0014-knn-classifiers.svg
    data/
      fig-xxxx.points.csv
      fig-xxxx.calibration.json
    validation/
      fig-xxxx-rendered-preview.png
      fig-xxxx-overlay.png
      fig-xxxx-report.json
```

The primary Markdown may reference only `MIT-Quant-Bible.vectorized.assets/svg/*.svg` and must not reference raster asset folders.

## Procedure

1. Build the complete figure inventory first.
   Create `docs/MIT-Quant-Bible.vectorized.assets/figures.manifest.json` before modifying Markdown.
   Every visual figure must have a manifest entry.
   Use [asset-classification.md](./references/asset-classification.md).

2. Detect whether each figure is already vector or embedded raster.
   Inspect PDF internals rather than guessing.
   Use the PDF inspection and routing guidance in [pdf-vector-extraction.md](./references/pdf-vector-extraction.md).

3. For `pdf_vector`, extract 1:1.
   Preserve vector geometry and text where possible.
   Reject any extraction that sneaks raster payloads back in.

4. For `embedded_raster`, reconstruct semantically.
   Use [reconstruction-workflow.md](./references/reconstruction-workflow.md).
   Record geometry, axes, scales, coordinate transforms, data extraction method, and styling decisions.

5. For `mixed`, split the problem.
   Extract the vector parts directly and reconstruct the raster parts semantically.
   For `unknown`, investigate before conversion rather than guessing.

6. Convert formulas, tables, and algorithms into native Markdown forms.
   Replace formulas with KaTeX-compatible math.
   Replace tables with Markdown tables where practical.
   Replace algorithms with structured Markdown or fenced pseudocode.

7. Embed SVGs with semantic notes.
   Reference only standalone SVG files from `docs/MIT-Quant-Bible.vectorized.assets/svg/`.
   Add meaningful alt text, figure notes, and SVG metadata.

8. Validate before reporting success.
   Run the checks in [validation-checklist.md](./references/validation-checklist.md).
   Produce `docs/MIT-Quant-Bible.vectorized.assets/conversion_report.md` using [conversion-report-template.md](./references/conversion-report-template.md).

## Required Agent Behavior

1. Do not stop after converting only easy figures.
2. Process every figure in the manifest.
3. Never use raster fallback.
4. Never use traced SVG as the final output.
5. Prefer direct PDF vector extraction when possible.
6. Prefer semantic SVG reconstruction for raster graphs.
7. Keep source-to-output traceability through the manifest.
8. Validate every output before reporting success.
9. If quality is poor, rebuild rather than rationalize it.
10. If exact reconstruction is impossible, mark `needs-human-review`.

## Success Definition

The task is complete only when every visual figure has a manifest entry, every already-vector figure has been extracted or marked with a reason, every raster graph has been semantically reconstructed or marked `needs-human-review`, every SVG remains crisp at high zoom, text is real SVG text where possible, axes, ticks, and grids are real SVG primitives, data marks are semantic SVG primitives, Markdown references only SVG or native assets, no raster files are referenced by the primary Markdown, and the validation report proves those checks.

A low-resolution-looking traced SVG is a failed conversion.

## References

- Inventory and classification: [asset-classification.md](./references/asset-classification.md)
- PDF inspection and direct extraction: [pdf-vector-extraction.md](./references/pdf-vector-extraction.md)
- Semantic reconstruction workflow: [reconstruction-workflow.md](./references/reconstruction-workflow.md)
- Validation checklist: [validation-checklist.md](./references/validation-checklist.md)
- Report template: [conversion-report-template.md](./references/conversion-report-template.md)