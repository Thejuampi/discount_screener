//! Enforcement of the table discipline (FR-42, FR-43, FR-44).
//!
//! The rules the project chose are only worth choosing if they are checked:
//!
//! * behaviour is expressed as `Scenario Outline` + `Examples`, never as a bare
//!   `Scenario` — a one-off scenario is the exception the manifest exists to make
//!   expensive, so the plain form is simply rejected;
//! * every Examples table is rectangular, has a leading `case` column, and has no
//!   empty cells;
//! * absence is spelled `ABSENT` and nothing else — `-`, `n/a`, `null`, `none`,
//!   `TBD` and an empty cell are all rejected, because each of them is a way of
//!   writing "missing" that some reader will eventually parse as a value;
//! * every outline has a manifest entry, and every manifest entry names a real
//!   outline.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

const ABSENT: &str = "ABSENT";

/// Spellings of absence that must never appear in a table. Each is a token a
/// reader or a parser could plausibly coerce to zero or to an empty string.
const FORBIDDEN_ABSENCE_TOKENS: &[&str] = &[
    "-", "--", "", "n/a", "N/A", "na", "NA", "null", "NULL", "nil", "none", "None", "NONE", "?",
    "TBD", "tbd", "missing", "unknown",
];

struct Outline {
    feature: String,
    name: String,
}

struct Table {
    feature: String,
    outline: String,
    header: Vec<String>,
    rows: Vec<Vec<String>>,
}

fn features_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/features")
}

fn feature_files() -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = fs::read_dir(features_dir())
        .expect("tests/features must exist")
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "feature"))
        .collect();
    files.sort();
    files
}

fn split_row(line: &str) -> Vec<String> {
    let trimmed = line.trim();
    let inner = trimmed
        .strip_prefix('|')
        .and_then(|rest| rest.strip_suffix('|'))
        .unwrap_or(trimmed);
    inner
        .split('|')
        .map(|cell| cell.trim().to_string())
        .collect()
}

/// Deliberately a small line-oriented reader rather than a full Gherkin parse.
/// The rules below are about the *text* an author writes, and a reader that sees
/// the same characters the author typed is the one that can enforce them.
fn parse(path: &Path) -> (Vec<Outline>, Vec<Table>) {
    let feature = path
        .file_name()
        .expect("feature file name")
        .to_string_lossy()
        .to_string();
    let body = fs::read_to_string(path).expect("read feature file");

    let mut outlines = Vec::new();
    let mut tables: Vec<Table> = Vec::new();
    let mut current_outline = String::new();
    let mut in_examples = false;

    for line in body.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('#') {
            continue;
        }
        if let Some(name) = trimmed.strip_prefix("Scenario Outline:") {
            current_outline = name.trim().to_string();
            outlines.push(Outline {
                feature: feature.clone(),
                name: current_outline.clone(),
            });
            in_examples = false;
            continue;
        }
        if trimmed.starts_with("Examples:") {
            in_examples = true;
            tables.push(Table {
                feature: feature.clone(),
                outline: current_outline.clone(),
                header: Vec::new(),
                rows: Vec::new(),
            });
            continue;
        }
        if trimmed.starts_with('|') && in_examples {
            let cells = split_row(trimmed);
            let table = tables.last_mut().expect("a table was opened by Examples:");
            if table.header.is_empty() {
                table.header = cells;
            } else {
                table.rows.push(cells);
            }
            continue;
        }
        if !trimmed.starts_with('|') && !trimmed.is_empty() {
            in_examples = false;
        }
    }
    (outlines, tables)
}

fn all_tables() -> Vec<Table> {
    feature_files()
        .iter()
        .flat_map(|path| parse(path).1)
        .collect()
}

fn all_outlines() -> Vec<Outline> {
    feature_files()
        .iter()
        .flat_map(|path| parse(path).0)
        .collect()
}

#[test]
fn every_feature_expresses_behaviour_as_outlines_never_as_bare_scenarios() {
    let offenders: Vec<String> = feature_files()
        .iter()
        .filter(|path| {
            fs::read_to_string(path)
                .expect("read feature file")
                .lines()
                .map(str::trim)
                .any(|line| line.starts_with("Scenario:"))
        })
        .map(|path| path.file_name().unwrap().to_string_lossy().to_string())
        .collect();
    assert!(
        offenders.is_empty(),
        "bare `Scenario:` found in {offenders:?} — add a row to an Examples table instead"
    );
}

#[test]
fn every_examples_table_leads_with_a_case_column() {
    let offenders: Vec<String> = all_tables()
        .iter()
        .filter(|table| table.header.first().map(String::as_str) != Some("case"))
        .map(|table| format!("{}::{}", table.feature, table.outline))
        .collect();
    assert!(
        offenders.is_empty(),
        "Examples tables must lead with a `case` column: {offenders:?}"
    );
}

#[test]
fn every_examples_row_is_rectangular() {
    let offenders: Vec<String> = all_tables()
        .iter()
        .flat_map(|table| {
            table
                .rows
                .iter()
                .filter(|row| row.len() != table.header.len())
                .map(|row| {
                    format!(
                        "{}::{} row {:?} has {} cells, header has {}",
                        table.feature,
                        table.outline,
                        row.first().cloned().unwrap_or_default(),
                        row.len(),
                        table.header.len()
                    )
                })
                .collect::<Vec<_>>()
        })
        .collect();
    assert!(offenders.is_empty(), "ragged Examples rows: {offenders:?}");
}

#[test]
fn absence_is_spelled_only_with_the_reserved_token() {
    let offenders: Vec<String> = all_tables()
        .iter()
        .flat_map(|table| {
            table
                .rows
                .iter()
                .flat_map(|row| {
                    row.iter()
                        .enumerate()
                        .filter(|(_, value)| FORBIDDEN_ABSENCE_TOKENS.contains(&value.as_str()))
                        .map(|(index, value)| {
                            format!(
                                "{}::{} row {:?} column {:?} says {value:?}, use {ABSENT}",
                                table.feature,
                                table.outline,
                                row.first().cloned().unwrap_or_default(),
                                table.header.get(index).cloned().unwrap_or_default()
                            )
                        })
                        .collect::<Vec<_>>()
                })
                .collect::<Vec<_>>()
        })
        .collect();
    assert!(
        offenders.is_empty(),
        "absence must be spelled {ABSENT}: {offenders:?}"
    );
}

#[test]
fn case_identifiers_are_unique_within_a_table() {
    let offenders: Vec<String> = all_tables()
        .iter()
        .filter_map(|table| {
            let ids: Vec<&String> = table.rows.iter().filter_map(|row| row.first()).collect();
            let unique: BTreeSet<&&String> = ids.iter().collect();
            (unique.len() != ids.len()).then(|| format!("{}::{}", table.feature, table.outline))
        })
        .collect();
    assert!(
        offenders.is_empty(),
        "duplicate case identifiers in {offenders:?}"
    );
}

#[test]
fn every_outline_is_justified_in_the_manifest() {
    let manifest: toml::Value = toml::from_str(
        &fs::read_to_string(features_dir().join("manifest.toml")).expect("read manifest"),
    )
    .expect("parse manifest");
    let declared: BTreeSet<String> = manifest
        .get("outline")
        .and_then(toml::Value::as_array)
        .expect("manifest must declare [[outline]] entries")
        .iter()
        .map(|entry| {
            format!(
                "{}::{}",
                entry
                    .get("feature")
                    .and_then(toml::Value::as_str)
                    .unwrap_or(""),
                entry
                    .get("name")
                    .and_then(toml::Value::as_str)
                    .unwrap_or("")
            )
        })
        .collect();
    let present: BTreeSet<String> = all_outlines()
        .iter()
        .map(|outline| format!("{}::{}", outline.feature, outline.name))
        .collect();
    assert_eq!(
        present, declared,
        "every Scenario Outline needs a manifest entry and vice versa"
    );
}

#[test]
fn every_manifest_entry_states_why_no_existing_table_covers_it() {
    let manifest: toml::Value = toml::from_str(
        &fs::read_to_string(features_dir().join("manifest.toml")).expect("read manifest"),
    )
    .expect("parse manifest");
    let offenders: Vec<String> = manifest
        .get("outline")
        .and_then(toml::Value::as_array)
        .expect("manifest must declare [[outline]] entries")
        .iter()
        .filter(|entry| {
            entry
                .get("why_new")
                .and_then(toml::Value::as_str)
                .is_none_or(|why| why.trim().is_empty())
        })
        .map(|entry| {
            entry
                .get("id")
                .and_then(toml::Value::as_str)
                .unwrap_or("<no id>")
                .to_string()
        })
        .collect();
    assert!(
        offenders.is_empty(),
        "manifest entries missing `why_new`: {offenders:?}"
    );
}
