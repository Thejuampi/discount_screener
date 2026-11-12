//! Saved Detail is display evidence. Exact ticker search can also read its identity.

use crate::engine::SymbolDetail;
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum DetailAssetType {
    Stock,
    Etf,
    Crypto,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum DetailSource {
    Session,
    Restored,
}

/// Add provenance while preserving the existing flat Detail command fields.
#[derive(Serialize)]
pub struct DetailResponse {
    #[serde(flatten)]
    pub(crate) detail: SymbolDetail,
    pub(crate) detail_source: DetailSource,
    pub(crate) saved_at_epoch_seconds: Option<i64>,
    pub(crate) asset_type: Option<DetailAssetType>,
}

impl DetailResponse {
    pub(crate) fn session(detail: SymbolDetail, asset_type: Option<DetailAssetType>) -> Self {
        Self {
            detail,
            detail_source: DetailSource::Session,
            saved_at_epoch_seconds: None,
            asset_type,
        }
    }
}

#[derive(Deserialize, Serialize)]
pub(crate) struct SavedTickerDetail {
    pub(crate) detail: SymbolDetail,
    pub(crate) asset_type: Option<DetailAssetType>,
    #[serde(skip)]
    pub(crate) saved_at_epoch_ms: i64,
}

impl SavedTickerDetail {
    pub(crate) fn new(
        mut detail: SymbolDetail,
        asset_type: Option<DetailAssetType>,
        saved_at_epoch_ms: i64,
    ) -> Self {
        // These decorations require session candles. Preserve summary values
        // and their model/source evidence without saving the candle series.
        detail.technical_breakdown = None;
        detail.chart_patterns.clear();
        detail.fib = None;
        Self {
            detail,
            asset_type,
            saved_at_epoch_ms,
        }
    }

    pub(crate) fn restore(self) -> DetailResponse {
        DetailResponse {
            detail: self.detail,
            detail_source: DetailSource::Restored,
            saved_at_epoch_seconds: Some(self.saved_at_epoch_ms / 1000),
            asset_type: self.asset_type,
        }
    }
}
