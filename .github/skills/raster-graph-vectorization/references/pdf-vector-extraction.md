# PDF Figure Analysis and Direct Vector Extraction

Use this workflow to determine whether each figure is already vector in the PDF and to extract it without rasterizing.

## 1. Detect Whether the Figure Is Vector or Raster

Inspect the PDF internals for each page or figure.

Useful tools include:

```text
pdfimages -list
mutool show
mutool draw -F svg
pdftocairo -svg
PyMuPDF page.get_images()
PyMuPDF page.get_drawings()
PyMuPDF page.get_text()
```

Classify the figure as one of:

- `pdf_vector`
- `embedded_raster`
- `mixed`
- `unknown`

If the structure is unclear, keep the figure as `unknown` and investigate. Do not guess.

## 2. Route by `source_kind`

- If `pdf_vector`, use direct vector extraction.
- If `embedded_raster`, use semantic reconstruction instead of tracing.
- If `mixed`, extract vector parts directly and reconstruct raster parts semantically.
- If `unknown`, inspect further before conversion.

## 3. Direct 1:1 Vector Extraction Path

Use this path for figures already represented as vector objects in the PDF.

1. Identify the figure bounding box on the PDF page.
2. Clip or export that region to standalone SVG.
3. Preserve drawing operators where possible.
4. Preserve text as text where possible.
5. Remove unrelated page content outside the figure.
6. Add `<title>`, `<desc>`, and optional `<metadata>`.
7. Validate that no raster payload exists.

## 4. Rejection Rules for Extracted SVG

The extracted SVG fails if it contains any of the following:

```text
<image
data:image
base64
.png
.jpg
.jpeg
.webp
.gif
```

If direct extraction produces raster fallback, reject it and use semantic reconstruction instead.

## 5. Quality Bar

- Preserve original vector paths, lines, text, geometry, colors, and layout.
- Do not rasterize then re-trace.
- Do not accept a nominally SVG output that is really just a wrapped raster.
- Keep the extracted figure standalone and Markdown-embeddable.
