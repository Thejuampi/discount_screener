//! A companyfacts document, cut down while it arrives.
//!
//! SEC sends about 4 MB per issuer. This app reads a few dozen concepts out of it, so the parsed
//! tree that every reader walks is mostly rows nobody asks for. The sieve copies the concepts the
//! readers name and drops the rest, over a 16 KB buffer, so the whole body never lands in memory.
//!
//! The desktop keeps more per fact than Android does. `annual_candidates_with_shape` reads `frame`
//! and `fy`, `extract_normalized_investment_evidence` reads `accn`, and `extract_current_shares`
//! accepts `10-Q` and `8-K`. Cut those here and the shares count and the investment evidence go
//! wrong with nothing to say so.
//!
//! A dimensional fact is dropped inside `us-gaap`, where every reader refuses one. Other
//! taxonomies keep their rows: `dei` carries the shares count, and no reader there looks at
//! `segment`.

use std::collections::HashSet;
use std::io::Read;

use crate::sec_driver_normalization_policy_generated as policy;

const FACT_FIELDS: &[&str] = &[
    "end", "start", "val", "filed", "form", "accn", "frame", "fy", "segment",
];

const EXTRA_QNAMES: &[&str] = &["EntityCommonStockSharesOutstanding"];

const BUFFER_BYTES: usize = 16 * 1024;

pub fn default_allowed_qnames() -> HashSet<&'static str> {
    let mut allowed: HashSet<&'static str> = HashSet::new();
    for list in [
        policy::DEVELOPMENT,
        policy::DEVELOPMENT_SOFTWARE,
        policy::DEVELOPMENT_WELLS,
        policy::DEVELOPMENT_INTANGIBLES,
        policy::DEVELOPMENT_AGGREGATE,
        policy::PROPERTY_ACQUISITION,
        policy::BUSINESS_ACQUISITION,
        EXTRA_QNAMES,
    ] {
        allowed.extend(list.iter().copied());
    }
    for operator in [
        policy::OPERATING_CASH_FLOW,
        policy::REVENUE,
        policy::INTEREST_EXPENSE,
        policy::TOTAL_DEBT,
        policy::CURRENT_DEBT,
        policy::NON_CURRENT_DEBT,
        policy::STOCKHOLDERS_EQUITY,
        policy::TAX_EXPENSE,
        policy::PRETAX_INCOME,
        policy::MARGINAL_TAX_REFERENCE,
        policy::DILUTED_AVERAGE_SHARES,
        policy::OPERATING_INCOME,
        policy::IMPAIRMENT_AGGREGATE,
        policy::IMPAIRMENT_COMPONENTS,
        policy::RESTRUCTURING_CHARGES,
    ] {
        allowed.extend(operator.qnames.iter().copied());
    }
    allowed
}

pub fn sieve<R: Read>(input: R) -> String {
    sieve_with(input, &default_allowed_qnames())
}

pub fn sieve_with<R: Read>(input: R, allowed: &HashSet<&str>) -> String {
    let mut reader = JsonStreamReader::new(input);
    let mut out: Vec<u8> = Vec::with_capacity(BUFFER_BYTES);
    out.extend_from_slice(b"{\"facts\":{");
    reader.skip_ws();
    if reader.peek() != Some(b'{') {
        out.extend_from_slice(b"}}");
        return String::from_utf8(out).unwrap_or_default();
    }
    reader.next_byte();
    let mut first = true;
    while let Some(key) = reader.next_member() {
        if key == "facts" {
            copy_facts(&mut reader, &mut out, allowed, &mut first);
        } else {
            reader.skip_value();
        }
    }
    out.extend_from_slice(b"}}");
    String::from_utf8(out).unwrap_or_default()
}

fn copy_facts(
    reader: &mut JsonStreamReader<impl Read>,
    out: &mut Vec<u8>,
    allowed: &HashSet<&str>,
    first: &mut bool,
) {
    reader.skip_ws();
    if reader.peek() != Some(b'{') {
        reader.skip_value();
        return;
    }
    reader.next_byte();
    while let Some(taxonomy) = reader.next_member() {
        let mut body: Vec<u8> = Vec::new();
        let dimensional_is_dead = taxonomy == "us-gaap";
        copy_taxonomy(reader, &mut body, allowed, dimensional_is_dead);
        if body.is_empty() {
            continue;
        }
        if !*first {
            out.push(b',');
        }
        *first = false;
        out.push(b'"');
        out.extend_from_slice(taxonomy.as_bytes());
        out.extend_from_slice(b"\":{");
        out.extend_from_slice(&body);
        out.push(b'}');
    }
}

fn copy_taxonomy(
    reader: &mut JsonStreamReader<impl Read>,
    out: &mut Vec<u8>,
    allowed: &HashSet<&str>,
    dimensional_is_dead: bool,
) {
    reader.skip_ws();
    if reader.peek() != Some(b'{') {
        reader.skip_value();
        return;
    }
    reader.next_byte();
    let mut first = true;
    while let Some(concept) = reader.next_member() {
        if !allowed.contains(concept.as_str()) {
            reader.skip_value();
            continue;
        }
        let mut body: Vec<u8> = Vec::new();
        copy_concept(reader, &mut body, dimensional_is_dead);
        if body.is_empty() {
            continue;
        }
        if !first {
            out.push(b',');
        }
        first = false;
        out.push(b'"');
        out.extend_from_slice(concept.as_bytes());
        out.extend_from_slice(b"\":{\"units\":{");
        out.extend_from_slice(&body);
        out.extend_from_slice(b"}}");
    }
}

fn copy_concept(
    reader: &mut JsonStreamReader<impl Read>,
    out: &mut Vec<u8>,
    dimensional_is_dead: bool,
) {
    reader.skip_ws();
    if reader.peek() != Some(b'{') {
        reader.skip_value();
        return;
    }
    reader.next_byte();
    while let Some(key) = reader.next_member() {
        if key == "units" {
            copy_units(reader, out, dimensional_is_dead);
        } else {
            reader.skip_value();
        }
    }
}

fn copy_units(
    reader: &mut JsonStreamReader<impl Read>,
    out: &mut Vec<u8>,
    dimensional_is_dead: bool,
) {
    reader.skip_ws();
    if reader.peek() != Some(b'{') {
        reader.skip_value();
        return;
    }
    reader.next_byte();
    let mut first = true;
    while let Some(unit) = reader.next_member() {
        let mut body: Vec<u8> = Vec::new();
        copy_fact_array(reader, &mut body, dimensional_is_dead);
        if body.is_empty() {
            continue;
        }
        if !first {
            out.push(b',');
        }
        first = false;
        out.push(b'"');
        out.extend_from_slice(unit.as_bytes());
        out.extend_from_slice(b"\":[");
        out.extend_from_slice(&body);
        out.push(b']');
    }
}

fn copy_fact_array(
    reader: &mut JsonStreamReader<impl Read>,
    out: &mut Vec<u8>,
    dimensional_is_dead: bool,
) {
    reader.skip_ws();
    if reader.peek() != Some(b'[') {
        reader.skip_value();
        return;
    }
    reader.next_byte();
    let mut first = true;
    loop {
        reader.skip_ws();
        match reader.peek() {
            None => return,
            Some(b']') => {
                reader.next_byte();
                return;
            }
            Some(b',') => {
                reader.next_byte();
                continue;
            }
            _ => {}
        }
        let mut fact: Vec<u8> = Vec::new();
        if copy_fact(reader, &mut fact, dimensional_is_dead) {
            if !first {
                out.push(b',');
            }
            first = false;
            out.extend_from_slice(&fact);
        }
    }
}

fn copy_fact(
    reader: &mut JsonStreamReader<impl Read>,
    out: &mut Vec<u8>,
    dimensional_is_dead: bool,
) -> bool {
    reader.skip_ws();
    if reader.peek() != Some(b'{') {
        reader.skip_value();
        return false;
    }
    reader.next_byte();
    let mut fields: Vec<u8> = Vec::new();
    let mut value: Vec<u8> = Vec::new();
    let mut dimensional = false;
    let mut first = true;
    while let Some(key) = reader.next_member() {
        if !FACT_FIELDS.contains(&key.as_str()) {
            reader.skip_value();
            continue;
        }
        value.clear();
        reader.copy_value(&mut value);
        if key == "segment" && value != b"null" {
            dimensional = true;
        }
        if !first {
            fields.push(b',');
        }
        first = false;
        fields.push(b'"');
        fields.extend_from_slice(key.as_bytes());
        fields.extend_from_slice(b"\":");
        fields.extend_from_slice(&value);
    }
    if dimensional && dimensional_is_dead {
        return false;
    }
    out.push(b'{');
    out.extend_from_slice(&fields);
    out.push(b'}');
    true
}

struct JsonStreamReader<R: Read> {
    source: R,
    buffer: Vec<u8>,
    length: usize,
    at: usize,
    drained: bool,
}

impl<R: Read> JsonStreamReader<R> {
    fn new(source: R) -> Self {
        JsonStreamReader {
            source,
            buffer: vec![0u8; BUFFER_BYTES],
            length: 0,
            at: 0,
            drained: false,
        }
    }

    fn fill(&mut self) -> bool {
        if self.drained {
            return false;
        }
        self.at = 0;
        self.length = 0;
        match self.source.read(&mut self.buffer) {
            Ok(0) | Err(_) => {
                self.drained = true;
                false
            }
            Ok(read) => {
                self.length = read;
                true
            }
        }
    }

    fn peek(&mut self) -> Option<u8> {
        if self.at >= self.length && !self.fill() {
            return None;
        }
        Some(self.buffer[self.at])
    }

    fn next_byte(&mut self) -> Option<u8> {
        let byte = self.peek()?;
        self.at += 1;
        Some(byte)
    }

    fn skip_ws(&mut self) {
        while let Some(byte) = self.peek() {
            if byte.is_ascii_whitespace() {
                self.at += 1;
            } else {
                return;
            }
        }
    }

    /// The next `"key":` of an object, or `None` at its closing brace.
    fn next_member(&mut self) -> Option<String> {
        loop {
            self.skip_ws();
            match self.peek()? {
                b'}' => {
                    self.at += 1;
                    return None;
                }
                b',' => {
                    self.at += 1;
                }
                b'"' => break,
                _ => return None,
            }
        }
        let key = self.read_string();
        self.skip_ws();
        if self.peek() == Some(b':') {
            self.at += 1;
        }
        self.skip_ws();
        Some(key)
    }

    fn read_string(&mut self) -> String {
        let mut raw: Vec<u8> = Vec::new();
        self.copy_string(&mut raw);
        if raw.len() < 2 {
            return String::new();
        }
        let body = &raw[1..raw.len() - 1];
        if body.contains(&b'\\') {
            unescape(body)
        } else {
            String::from_utf8_lossy(body).into_owned()
        }
    }

    fn copy_string(&mut self, out: &mut Vec<u8>) {
        if self.peek() != Some(b'"') {
            return;
        }
        out.push(b'"');
        self.at += 1;
        loop {
            let Some(byte) = self.next_byte() else { return };
            out.push(byte);
            if byte == b'\\' {
                if let Some(escaped) = self.next_byte() {
                    out.push(escaped);
                }
                continue;
            }
            if byte == b'"' {
                return;
            }
        }
    }

    fn copy_value(&mut self, out: &mut Vec<u8>) {
        self.skip_ws();
        match self.peek() {
            Some(b'"') => self.copy_string(out),
            Some(b'{') => self.copy_nested(out, b'{', b'}'),
            Some(b'[') => self.copy_nested(out, b'[', b']'),
            _ => {
                while let Some(byte) = self.peek() {
                    if byte == b',' || byte == b'}' || byte == b']' || byte.is_ascii_whitespace() {
                        return;
                    }
                    out.push(byte);
                    self.at += 1;
                }
            }
        }
    }

    fn copy_nested(&mut self, out: &mut Vec<u8>, open: u8, close: u8) {
        let mut depth = 0usize;
        loop {
            self.skip_ws();
            let Some(byte) = self.peek() else { return };
            if byte == b'"' {
                self.copy_string(out);
                continue;
            }
            self.at += 1;
            out.push(byte);
            if byte == open {
                depth += 1;
            } else if byte == close {
                depth -= 1;
                if depth == 0 {
                    return;
                }
            }
        }
    }

    fn skip_value(&mut self) {
        let mut sink: Vec<u8> = Vec::new();
        self.copy_value(&mut sink);
    }
}

fn unescape(body: &[u8]) -> String {
    let text = String::from_utf8_lossy(body);
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars();
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            out.push(ch);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some('r') => out.push('\r'),
            Some('b') => out.push('\u{0008}'),
            Some('f') => out.push('\u{000c}'),
            Some('u') => {
                let hex: String = chars.by_ref().take(4).collect();
                if let Some(point) = u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                    out.push(point);
                }
            }
            Some(other) => out.push(other),
            None => return out,
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jpm() -> String {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../android/core/src/test/resources/sec-companyfacts/JPM.json");
        std::fs::read_to_string(path).expect("JPM companyfacts fixture")
    }

    fn facts_of(body: &str) -> String {
        format!(
            "{{\"cik\":19617,\"entityName\":\"X\",\"facts\":{{\"us-gaap\":{{\"InterestExpense\":{{\"label\":\"Interest\",\"units\":{{\"USD\":[{body}]}}}}}}}}}}"
        )
    }

    #[test]
    fn an_unused_concept_never_reaches_the_output() {
        let raw = "{\"facts\":{\"us-gaap\":{\"HugeUnusedConcept\":{\"label\":\"junk {\\\"end\\\":1}\",\"units\":{\"USD\":[{\"val\":9}]}}}}}";
        assert!(!sieve(raw.as_bytes()).contains("HugeUnusedConcept"));
    }

    #[test]
    fn the_fields_the_desktop_reads_stay_in_the_output() {
        let raw = facts_of(
            "{\"fp\":\"FY\",\"form\":\"10-K\",\"end\":\"2024-12-31\",\"val\":1,\"accn\":\"a\",\"fy\":2024,\"frame\":\"CY2024\",\"filed\":\"2025-02-01\"}",
        );
        assert_eq!(
            "{\"facts\":{\"us-gaap\":{\"InterestExpense\":{\"units\":{\"USD\":[{\"form\":\"10-K\",\"end\":\"2024-12-31\",\"val\":1,\"accn\":\"a\",\"fy\":2024,\"frame\":\"CY2024\",\"filed\":\"2025-02-01\"}]}}}}}",
            sieve(raw.as_bytes())
        );
    }

    #[test]
    fn a_dimensional_us_gaap_fact_never_reaches_the_output() {
        let raw = facts_of(
            "{\"form\":\"10-K\",\"end\":\"2024-12-31\",\"val\":1,\"segment\":{\"dim\":\"Americas\"}}",
        );
        assert!(!sieve(raw.as_bytes()).contains("Americas"));
    }

    #[test]
    fn the_shares_count_survives_the_sieve() {
        let raw = "{\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"shares\":[{\"form\":\"10-Q\",\"end\":\"2025-06-30\",\"val\":2770000000,\"filed\":\"2025-07-30\"}]}}}}}";
        assert!(sieve(raw.as_bytes()).contains("2770000000"));
    }

    #[test]
    fn a_quarter_survives_the_sieve() {
        let raw = facts_of("{\"fp\":\"Q3\",\"form\":\"10-Q\",\"end\":\"2024-09-30\",\"val\":3}");
        assert!(sieve(raw.as_bytes()).contains("2024-09-30"));
    }

    /// The cost the sieve exists to hold down. A real document carries hundreds of concepts this
    /// app never names, each with a label, a description and years of quarters. The fixture in the
    /// repo is already a slice of one, so the share it keeps says nothing; this document has the
    /// shape of the file SEC sends.
    #[test]
    fn the_sieve_keeps_under_a_fifth_of_a_document_shaped_like_the_source() {
        let unused: Vec<String> = (0..40)
            .map(|i| {
                format!(
                    "\"UnusedConcept{i}\":{{\"label\":\"A label nobody reads\",\"description\":\"A long description nobody reads either.\",\"units\":{{\"USD\":[{{\"fp\":\"Q1\",\"form\":\"10-Q\",\"end\":\"2024-03-31\",\"val\":{i},\"accn\":\"0000019617-24-000001\",\"fy\":2024,\"frame\":\"CY2024Q1\",\"filed\":\"2024-04-01\"}}]}}}}"
                )
            })
            .collect();
        let raw = format!(
            "{{\"cik\":19617,\"facts\":{{\"us-gaap\":{{\"InterestExpense\":{{\"label\":\"Interest\",\"units\":{{\"USD\":[{{\"form\":\"10-K\",\"end\":\"2024-12-31\",\"val\":1,\"filed\":\"2025-02-01\"}}]}}}},{}}}}}}}",
            unused.join(",")
        );
        let slim = sieve(raw.as_bytes());
        assert!(
            slim.len() * 5 < raw.len(),
            "kept {} of {} bytes",
            slim.len(),
            raw.len()
        );
    }

    #[test]
    fn the_sieved_copy_is_json_the_readers_can_walk() {
        let slim: serde_json::Value = serde_json::from_str(&sieve(jpm().as_bytes())).expect("json");
        assert!(slim.pointer("/facts/us-gaap").is_some());
    }
}
