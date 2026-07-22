# Semantic Reconstruction Workflow

Use this workflow for raster charts, plots, and raster figures that cannot be extracted directly from PDF vector operators.

The source raster is reference only. Do not trace it and do not ship any traced output as the final asset.

## 1. Inventory First

Do not start bulk replacement until `docs/MIT-Quant-Bible.vectorized.assets/figures.manifest.json` exists and the figure has a manifest entry.

## 2. Determine Source Geometry

Record these values for each figure:

```text
source_width_px =
source_height_px =
figure_bbox_px =
plot_left_px =
plot_right_px =
plot_top_px =
plot_bottom_px =
plot_width_px =
plot_height_px =
```

The plot rectangle is the data area only. Exclude labels, title, legend, captions, and margins.

## 3. Infer Axes

For every quantitative chart, record:

```text
x_axis_label =
x_axis_min =
x_axis_max =
x_tick_values =
x_tick_pixel_positions =
x_scale = linear | log | categorical | date | unknown

y_axis_label =
y_axis_min =
y_axis_max =
y_tick_values =
y_tick_pixel_positions =
y_scale = linear | log | categorical | date | unknown
```

If axes are missing or implied, document the inference explicitly.

## 4. Define Coordinate Transform

For ordinary Cartesian charts:

```text
plot_width_px = plot_right_px - plot_left_px
plot_height_px = plot_bottom_px - plot_top_px

svg_x = plot_left_px + ((data_x - x_axis_min) / (x_axis_max - x_axis_min)) * plot_width_px

svg_y = plot_bottom_px - ((data_y - y_axis_min) / (y_axis_max - y_axis_min)) * plot_height_px
```

For logarithmic axes:

```text
svg_x = plot_left_px + ((log(data_x) - log(x_axis_min)) / (log(x_axis_max) - log(x_axis_min))) * plot_width_px
```

For categorical axes, define category centers explicitly.

## 5. Rebuild Chart Scaffolding

Recreate these as clean SVG primitives:

- canvas or background
- plot area
- grid lines
- axes
- tick marks
- tick labels
- axis labels
- title
- legend
- annotations
- reference lines

Text must be SVG `<text>`, not traced outlines. Grid lines must be SVG `<line>` or `<path>`, not traced jagged blobs.

## 6. Reconstruct Data Marks

Use semantic SVG primitives for the actual marks.

| Chart type | Required representation |
| --- | --- |
| Smooth curve | Fitted `<path>` or sampled `<polyline>` |
| Line chart | `<path>` or `<polyline>` |
| Scatter plot | `<circle>` or marker symbol |
| Bar chart | `<rect>` |
| Histogram | `<rect>` |
| Area chart | Closed `<path>` |
| Confidence band | `<polygon>` or `<path>` |
| Box plot | `<rect>`, `<line>`, `<circle>` |
| Heatmap | Grid of `<rect>` |
| Contour plot | Multiple semantic `<path>` elements |
| Surface plot | Clean SVG approximation plus explicit figure note |
| Decision boundary | Semantic boundary paths plus points or regions |

If the graph represents a known mathematical curve, fit the curve and sample it.
If the graph is empirical, digitize points from the raster, store the points as CSV in `docs/MIT-Quant-Bible.vectorized.assets/data/`, and render them as SVG primitives.
Do not draw approximate cartoons.

## 7. Rebuild Text and Labels

All labels must be real text, for example `<text>Probability density</text>`.

If the exact font is unknown, use a web-safe approximation such as `Arial, Helvetica, sans-serif` or `Times New Roman, Times, serif`.

## 8. Preserve Style Without Rasterizing

Match the original canvas size, aspect ratio, plot rectangle, font size, font weight, stroke width, dash patterns, curve colors, marker sizes, legend placement, label placement, and annotation positions as closely as possible.

Preserve style through vector geometry, not through raster fallback.

## 9. SVG Metadata Requirements

Every standalone SVG must contain accessible labeling and semantic description.

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="..." role="img" aria-labelledby="title desc">
  <title id="title">...</title>
  <desc id="desc">...</desc>
  <metadata type="application/json">...</metadata>
</svg>
```

The `<desc>` should explain what the figure shows, what the axes mean, the key trends, important values, and the interpretation.

## 10. Markdown Embedding

Reference standalone SVG files from `docs/MIT-Quant-Bible.vectorized.assets/svg/`.

Good pattern:

```md
![Right-skewed probability density curve. The x-axis ranges from 0 to 16 and the y-axis ranges from 0 to 0.30. The curve peaks near x=2.3 and decays with a long right tail.](MIT-Quant-Bible.vectorized.assets/svg/fig-xxxx-probability-density.svg)

**Figure note:** This graph shows a right-skewed probability density over x in [0, 16]. The density rises sharply, peaks around x approximately 2.3, then decays gradually toward zero.
```

The primary Markdown may reference only `MIT-Quant-Bible.vectorized.assets/svg/*.svg` for figure assets.
