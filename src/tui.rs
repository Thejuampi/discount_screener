use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::style::Color as RatatuiColor;
use ratatui::widgets::Clear;
use ratatui::widgets::Widget;

use super::INLINE_STYLE_MARKER;
use super::RenderLine;
use super::decode_color_marker;
use super::normalize_frame_into;

pub(super) struct RenderLineFrame<'a> {
    lines: &'a [RenderLine],
    viewport_width: usize,
    viewport_height: usize,
}

impl<'a> RenderLineFrame<'a> {
    pub(super) fn new(
        lines: &'a [RenderLine],
        viewport_width: usize,
        viewport_height: usize,
    ) -> Self {
        Self {
            lines,
            viewport_width,
            viewport_height,
        }
    }
}

impl Widget for RenderLineFrame<'_> {
    fn render(self, area: Rect, buffer: &mut Buffer) {
        render_lines_to_buffer(
            self.lines,
            self.viewport_width.min(area.width as usize),
            self.viewport_height.min(area.height as usize),
            area,
            buffer,
        );
    }
}

#[cfg(test)]
pub(super) fn buffer_from_render_lines(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
) -> Buffer {
    let area = Rect::new(0, 0, viewport_width as u16, viewport_height as u16);
    let mut buffer = Buffer::empty(area);
    render_lines_to_buffer(lines, viewport_width, viewport_height, area, &mut buffer);
    buffer
}

fn render_lines_to_buffer(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
    area: Rect,
    buffer: &mut Buffer,
) {
    Clear.render(area, buffer);

    let mut normalized = Vec::with_capacity(viewport_height);
    normalize_frame_into(lines, viewport_width, viewport_height, &mut normalized);

    for (row_index, line) in normalized.iter().enumerate() {
        if row_index >= area.height as usize {
            break;
        }
        write_render_line_to_buffer(line, area, row_index, buffer);
    }
}

fn write_render_line_to_buffer(
    line: &RenderLine,
    area: Rect,
    row_index: usize,
    buffer: &mut Buffer,
) {
    let y = area.y + row_index as u16;
    let mut x = 0usize;
    let mut fg = ratatui_color(line.color);
    let mut bg = RatatuiColor::Reset;
    let mut chars = line.text.chars();

    while let Some(ch) = chars.next() {
        if ch == INLINE_STYLE_MARKER {
            if let Some(fg_code) = chars.next() {
                let bg_code = chars.next();
                fg = ratatui_color(decode_color_marker(fg_code));
                bg = bg_code
                    .and_then(decode_color_marker)
                    .map(ratatui_color_from)
                    .unwrap_or(RatatuiColor::Reset);
            }
            continue;
        }

        if x >= area.width as usize {
            break;
        }

        let cell = &mut buffer[(area.x + x as u16, y)];
        cell.set_char(ch);
        cell.set_fg(fg);
        cell.set_bg(bg);
        x += 1;
    }
}

fn ratatui_color(color: Option<crossterm::style::Color>) -> RatatuiColor {
    color.map(ratatui_color_from).unwrap_or(RatatuiColor::Reset)
}

fn ratatui_color_from(color: crossterm::style::Color) -> RatatuiColor {
    match color {
        crossterm::style::Color::Reset => RatatuiColor::Reset,
        crossterm::style::Color::Black => RatatuiColor::Black,
        crossterm::style::Color::DarkGrey => RatatuiColor::DarkGray,
        crossterm::style::Color::Grey => RatatuiColor::Gray,
        crossterm::style::Color::White => RatatuiColor::White,
        crossterm::style::Color::DarkRed => RatatuiColor::Red,
        crossterm::style::Color::Red => RatatuiColor::LightRed,
        crossterm::style::Color::DarkGreen => RatatuiColor::Green,
        crossterm::style::Color::Green => RatatuiColor::LightGreen,
        crossterm::style::Color::DarkYellow => RatatuiColor::Yellow,
        crossterm::style::Color::Yellow => RatatuiColor::LightYellow,
        crossterm::style::Color::DarkBlue => RatatuiColor::Blue,
        crossterm::style::Color::Blue => RatatuiColor::LightBlue,
        crossterm::style::Color::DarkMagenta => RatatuiColor::Magenta,
        crossterm::style::Color::Magenta => RatatuiColor::LightMagenta,
        crossterm::style::Color::DarkCyan => RatatuiColor::Cyan,
        crossterm::style::Color::Cyan => RatatuiColor::LightCyan,
        crossterm::style::Color::AnsiValue(value) => RatatuiColor::Indexed(value),
        crossterm::style::Color::Rgb { r, g, b } => RatatuiColor::Rgb(r, g, b),
    }
}

#[cfg(test)]
mod tests {
    use crossterm::style::Color;
    use ratatui::style::Color as RatatuiColor;

    use super::buffer_from_render_lines;
    use crate::RenderLine;
    use crate::StyledCell;
    use crate::styled_cells_line;

    #[test]
    fn buffer_bridge_renders_plain_lines_into_buffer_cells() {
        let buffer = buffer_from_render_lines(
            &[RenderLine {
                color: Some(Color::Yellow),
                text: "OK".to_string(),
            }],
            4,
            1,
        );

        let rendered = (0..4)
            .map(|col| {
                let cell = &buffer[(col as u16, 0u16)];
                (cell.symbol().to_string(), cell.fg, cell.bg)
            })
            .collect::<Vec<_>>();

        assert_eq!(
            rendered,
            vec![
                (
                    "O".to_string(),
                    RatatuiColor::LightYellow,
                    RatatuiColor::Reset
                ),
                (
                    "K".to_string(),
                    RatatuiColor::LightYellow,
                    RatatuiColor::Reset
                ),
                (" ".to_string(), RatatuiColor::Reset, RatatuiColor::Reset),
                (" ".to_string(), RatatuiColor::Reset, RatatuiColor::Reset),
            ]
        );
    }

    #[test]
    fn buffer_bridge_preserves_inline_foreground_and_background_styles() {
        let line = styled_cells_line(&[
            StyledCell {
                ch: 'A',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 1,
            },
            StyledCell {
                ch: 'B',
                color: Some(Color::Blue),
                bg_color: None,
                priority: 1,
            },
        ]);
        let buffer = buffer_from_render_lines(&[line], 2, 1);

        let rendered = (0..2)
            .map(|col| {
                let cell = &buffer[(col as u16, 0u16)];
                (cell.symbol().to_string(), cell.fg, cell.bg)
            })
            .collect::<Vec<_>>();

        assert_eq!(
            rendered,
            vec![
                (
                    "A".to_string(),
                    RatatuiColor::LightGreen,
                    RatatuiColor::LightRed
                ),
                (
                    "B".to_string(),
                    RatatuiColor::LightBlue,
                    RatatuiColor::Reset
                ),
            ]
        );
    }

    #[test]
    fn buffer_bridge_clips_visible_width_without_leaking_style_markers() {
        let buffer = buffer_from_render_lines(
            &[RenderLine {
                color: Some(Color::Cyan),
                text: "WIDE".to_string(),
            }],
            2,
            1,
        );

        let rendered = (0..2)
            .map(|col| buffer[(col as u16, 0u16)].symbol().to_string())
            .collect::<Vec<_>>();

        assert_eq!(rendered, vec!["W".to_string(), "I".to_string()]);
    }
}
