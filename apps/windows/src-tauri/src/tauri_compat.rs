//! Thin seam between the command layer and the Tauri runtime.
//!
//! With the `gui` feature this simply re-exports Tauri's types, so the desktop
//! app is unchanged. Without it (headless server builds, no gtk/webkit) it
//! provides minimal stand-ins so the very same command/engine code compiles and
//! can be called directly.

#[cfg(feature = "gui")]
pub use tauri::State;

#[cfg(feature = "gui")]
pub use tauri::async_runtime;

/// Headless stand-in for `tauri::State`: a borrowed reference to managed state.
#[cfg(not(feature = "gui"))]
pub struct State<'a, T: Send + Sync + 'static>(&'a T);

#[cfg(not(feature = "gui"))]
impl<'a, T: Send + Sync + 'static> State<'a, T> {
    #[allow(dead_code)]
    pub fn from_ref(value: &'a T) -> Self {
        Self(value)
    }
    #[allow(dead_code)]
    pub fn inner(&self) -> &'a T {
        self.0
    }
}

#[cfg(not(feature = "gui"))]
impl<T: Send + Sync + 'static> Clone for State<'_, T> {
    fn clone(&self) -> Self {
        Self(self.0)
    }
}

#[cfg(not(feature = "gui"))]
impl<T: Send + Sync + 'static> std::ops::Deref for State<'_, T> {
    type Target = T;
    fn deref(&self) -> &T {
        self.0
    }
}

#[cfg(not(feature = "gui"))]
pub mod async_runtime {
    /// Run blocking work on a dedicated thread; resolves like Tauri's helper.
    pub async fn spawn_blocking<F, R>(f: F) -> Result<R, String>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let (tx, rx) = tokio::sync::oneshot::channel();
        std::thread::spawn(move || {
            let _ = tx.send(f());
        });
        rx.await.map_err(|e| e.to_string())
    }
}
