// Crypto Scalping — real-time WebSocket feed (Phase 2b).
//
// A dedicated OS thread runs a current-thread tokio runtime that keeps a single
// Coinbase ticker subscription alive for the active product and pushes ticks to
// the frontend via Tauri events. The rest of the app stays blocking/sync; a
// `watch` channel carries the desired product from commands into the async task.

use futures_util::{SinkExt, StreamExt};
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Emitter};
use tokio::sync::watch;
use tokio_tungstenite::tungstenite::Message;

const WS_URL: &str = "wss://ws-feed.exchange.coinbase.com";

#[derive(Serialize, Clone)]
pub struct ScalpTick {
    pub product: String,
    pub price_cents: i64,
    pub bid_cents: i64,
    pub ask_cents: i64,
}

/// Spawn the background WebSocket thread.
pub fn spawn(app: AppHandle, rx: watch::Receiver<String>) {
    std::thread::Builder::new()
        .name("scalp-ws".into())
        .spawn(move || {
            if let Ok(rt) = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                rt.block_on(run(app, rx));
            }
        })
        .ok();
}

async fn run(app: AppHandle, mut rx: watch::Receiver<String>) {
    loop {
        let product = rx.borrow().clone();
        if product.is_empty() {
            // No product yet — wait for the first subscription.
            if rx.changed().await.is_err() {
                return;
            }
            continue;
        }
        let _ = stream_product(&app, &product, &mut rx).await;
        // Reconnect backoff (skip if a product change already requested).
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    }
}

/// Connect, subscribe to `product`'s ticker, and pump ticks until the connection
/// drops or the desired product changes.
async fn stream_product(
    app: &AppHandle,
    product: &str,
    rx: &mut watch::Receiver<String>,
) -> Result<(), ()> {
    let (ws, _) = tokio_tungstenite::connect_async(WS_URL)
        .await
        .map_err(|_| ())?;
    let (mut write, mut read) = ws.split();
    let sub = format!(
        r#"{{"type":"subscribe","product_ids":["{}"],"channels":["ticker"]}}"#,
        product
    );
    write.send(Message::Text(sub)).await.map_err(|_| ())?;

    loop {
        tokio::select! {
            msg = read.next() => match msg {
                Some(Ok(Message::Text(txt))) => {
                    if let Some(tick) = parse_tick(&txt) {
                        let _ = app.emit("scalp_tick", &tick);
                    }
                }
                Some(Ok(Message::Ping(p))) => { let _ = write.send(Message::Pong(p)).await; }
                Some(Ok(_)) => {}
                _ => return Err(()), // closed or error → reconnect
            },
            changed = rx.changed() => {
                if changed.is_err() { return Err(()); }
                return Ok(()); // product switched → drop & reconnect with the new one
            }
        }
    }
}

fn parse_tick(txt: &str) -> Option<ScalpTick> {
    let v: Value = serde_json::from_str(txt).ok()?;
    if v.get("type")?.as_str()? != "ticker" {
        return None;
    }
    let to_cents = |k: &str| {
        v.get(k)
            .and_then(|x| x.as_str())
            .and_then(|s| s.parse::<f64>().ok())
            .filter(|f| f.is_finite() && *f > 0.0)
            .map(|f| (f * 100.0).round() as i64)
    };
    Some(ScalpTick {
        product: v.get("product_id")?.as_str()?.to_string(),
        price_cents: to_cents("price")?,
        bid_cents: to_cents("best_bid").unwrap_or(0),
        ask_cents: to_cents("best_ask").unwrap_or(0),
    })
}
