# Figure Inventory and Classification

Build a complete figure inventory before editing the destination Markdown. Inventory every visual figure, not only the obvious raster graphs.

Do not modify `docs/MIT-Quant-Bible.md` or `docs/MIT-Quant-Bible.native.md` until `docs/MIT-Quant-Bible.vectorized.assets/figures.manifest.json` exists.

## Manifest Minimum Fields

Every figure needs a manifest entry.

```json
{
  "figure_id": "fig-0014-knn-classifiers",
  "source_pdf": "docs/MIT-Quant-Bible.pdf",
  "source_page": 14,
  "source_kind": "pdf_vector",
  "source_asset": "docs/MIT-Quant-Bible.raster-backup.assets/MIT-Quant-Bible.pdf-0014-00.png",
  "source_dimensions_px": {
    "width": 1024,
    "height": 768
  },
  "page_bbox_pdf_units": {
    "x0": null,
    "y0": null,
    "x1": null,
    "y1": null
  },
  "markdown_locations": [
    {
      "file": "docs/MIT-Quant-Bible.md",
      "anchor": "k-nearest neighbors"
    }
  ],
  "classification": "decision_boundary",
  "conversion_strategy": "direct_pdf_vector_extraction",
  "status": "pending",
  "notes": ""
}
```

## `source_kind` Values

- `pdf_vector`: The PDF already contains the figure as vector drawing operators and text.
- `embedded_raster`: The figure is fundamentally raster and needs semantic reconstruction.
- `mixed`: Part of the figure is vector and part is raster.
- `unknown`: The PDF structure is still unclear and needs investigation.

## Classification Values

Use these classifications in the manifest.

- `cartesian_plot`
- `scatter_plot`
- `density_curve`
- `decision_boundary`
- `diagram`
- `table`
- `formula`
- `algorithm`
- `mixed_figure`
- `unknown`

## Conversion Strategy Rules

| Figure type | Preferred strategy | Output |
| --- | --- | --- |
| `pdf_vector` figure | `direct_pdf_vector_extraction` | Standalone SVG preserving vector paths and text |
| `embedded_raster` quantitative chart | `semantic_svg_reconstruction` | Standalone semantic SVG |
| `embedded_raster` formula | `markdown_math` | KaTeX or Markdown math |
| `embedded_raster` table | `markdown_table` | Markdown table |
| `embedded_raster` logical diagram | `mermaid` or `semantic_svg_reconstruction` | Mermaid or semantic SVG |
| `mixed` | Split by component | SVG plus native Markdown as needed |
| `unknown` | `needs-human-review` until clarified | Investigation or explicit review marker |

## Source-of-Truth Order

1. Original source data, if available.
2. Existing vector or PDF primitives, if extractable.
3. Calibrated raster reconstruction.
4. Manual reconstruction using the raster as reference.
5. `needs-human-review`.

Do not use raw full-image autotrace as the primary method.

## Native Output Rules

- Standalone `.svg` built from real SVG primitives.
- KaTeX or Markdown math for formulas.
- Markdown tables where practical.
- Mermaid only for logical flow or structural diagrams, not quantitative charts.
- No final output that depends on raster payloads or traced bitmap geometry.
