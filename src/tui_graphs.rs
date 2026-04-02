use crossterm::style::Color;
use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::style::Color as RatatuiColor;
use ratatui::symbols::Marker;
use ratatui::widgets::Widget;
use ratatui::widgets::canvas::Canvas;
use ratatui::widgets::canvas::Line;
use ratatui::widgets::canvas::Points;

use super::StyledCell;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct BrailleDot {
    color: Option<Color>,
    priority: u8,
}

pub(super) struct BrailleCanvas {
    dots: Vec<BrailleDot>,
    width: usize,
    terminal_height: usize,
    min_value: f64,
    max_value: f64,
}

impl BrailleCanvas {
    pub(super) fn new(
        terminal_height: usize,
        width: usize,
        min_value: f64,
        max_value: f64,
    ) -> Self {
        Self {
            dots: vec![
                BrailleDot {
                    color: None,
                    priority: 0,
                };
                terminal_height * 4 * width * 2
            ],
            width,
            terminal_height,
            min_value,
            max_value,
        }
    }

    pub(super) fn map_to_subrow(&self, value: f64) -> usize {
        super::map_numeric_to_row(
            value,
            self.min_value,
            self.max_value,
            self.terminal_height * 4,
        )
    }

    pub(super) fn fill_dot(
        &mut self,
        subrow: usize,
        col: usize,
        dot_col: usize,
        color: Option<Color>,
        priority: u8,
    ) {
        let idx = self.dot_index(subrow, col, dot_col);
        let Some(dot) = idx.and_then(|idx| self.dots.get_mut(idx)) else {
            return;
        };
        if dot.priority > priority && dot.color.is_some() {
            return;
        }
        *dot = BrailleDot { color, priority };
    }

    pub(super) fn fill_vertical_half(
        &mut self,
        col: usize,
        dot_col: usize,
        subrow_lo: usize,
        subrow_hi: usize,
        color: Option<Color>,
        priority: u8,
    ) {
        let lo = subrow_lo.min(subrow_hi);
        let hi = subrow_lo.max(subrow_hi);
        for subrow in lo..=hi {
            self.fill_dot(subrow, col, dot_col, color, priority);
        }
    }

    pub(super) fn fill_vertical_full(
        &mut self,
        col: usize,
        subrow_lo: usize,
        subrow_hi: usize,
        color: Option<Color>,
        priority: u8,
    ) {
        self.fill_vertical_half(col, 0, subrow_lo, subrow_hi, color, priority);
        self.fill_vertical_half(col, 1, subrow_lo, subrow_hi, color, priority);
    }

    pub(super) fn plot_hline(&mut self, value: f64, color: Option<Color>, priority: u8) {
        let subrow = self.map_to_subrow(value);
        for col in 0..self.width {
            self.fill_vertical_full(col, subrow, subrow, color, priority);
        }
    }

    pub(super) fn collapse_to_cells(&self) -> Vec<Vec<StyledCell>> {
        let mut rows = Vec::with_capacity(self.terminal_height);
        for terminal_row in 0..self.terminal_height {
            let mut cells = Vec::with_capacity(self.width);
            for col in 0..self.width {
                let mut mask = 0u8;
                let mut color = None;
                let mut priority = 0u8;
                for subrow_offset in 0..4 {
                    for dot_col in 0..2 {
                        let subrow = terminal_row * 4 + subrow_offset;
                        let idx = ((subrow * self.width + col) * 2) + dot_col;
                        let dot = self.dots[idx];
                        if dot.color.is_none() {
                            continue;
                        }
                        mask |= braille_dot_mask(subrow_offset, dot_col);
                        if dot.priority >= priority {
                            priority = dot.priority;
                            color = dot.color;
                        }
                    }
                }
                if mask == 0 {
                    cells.push(StyledCell {
                        ch: ' ',
                        color: None,
                        bg_color: None,
                        priority: 0,
                    });
                    continue;
                }

                cells.push(StyledCell {
                    ch: char::from_u32(0x2800 + mask as u32).unwrap_or(' '),
                    color,
                    bg_color: None,
                    priority,
                });
            }
            rows.push(cells);
        }
        rows
    }

    fn dot_index(&self, subrow: usize, col: usize, dot_col: usize) -> Option<usize> {
        if subrow >= self.terminal_height * 4 || col >= self.width || dot_col > 1 {
            return None;
        }

        Some(((subrow * self.width + col) * 2) + dot_col)
    }
}

fn braille_dot_mask(subrow_offset: usize, dot_col: usize) -> u8 {
    match (subrow_offset, dot_col) {
        (0, 0) => 0x01,
        (1, 0) => 0x02,
        (2, 0) => 0x04,
        (3, 0) => 0x40,
        (0, 1) => 0x08,
        (1, 1) => 0x10,
        (2, 1) => 0x20,
        (3, 1) => 0x80,
        _ => 0,
    }
}

pub(super) fn render_line_chart(values: &[f64], width: usize, height: usize) -> Vec<String> {
    if values.is_empty() {
        return vec!["(no data)".to_string(); height.max(1)];
    }

    let chart_width = width.max(1);
    let chart_height = height.max(1);
    let min_value = values
        .iter()
        .fold(f64::INFINITY, |left, right| left.min(*right));
    let max_value = values
        .iter()
        .fold(f64::NEG_INFINITY, |left, right| left.max(*right));
    let max_y = if min_value == max_value {
        max_value + 1.0
    } else {
        max_value
    };
    let coords = values
        .iter()
        .enumerate()
        .map(|(index, value)| (index as f64, *value))
        .collect::<Vec<_>>();
    let area = Rect::new(0, 0, chart_width as u16, chart_height as u16);
    let mut buffer = Buffer::empty(area);
    let x_max = (coords.len().saturating_sub(1)).max(1) as f64;

    let widget = Canvas::default()
        .marker(Marker::Braille)
        .x_bounds([0.0, x_max])
        .y_bounds([min_value, max_y])
        .paint(|ctx| {
            for pair in coords.windows(2) {
                let start = pair[0];
                let end = pair[1];
                ctx.draw(&Line::new(
                    start.0,
                    start.1,
                    end.0,
                    end.1,
                    RatatuiColor::Cyan,
                ));
            }
            ctx.draw(&Points::new(&coords, RatatuiColor::Cyan));
        });
    widget.render(area, &mut buffer);

    (0..chart_height)
        .map(|row| {
            (0..chart_width)
                .map(|col| buffer[(col as u16, row as u16)].symbol())
                .collect::<String>()
        })
        .collect()
}
