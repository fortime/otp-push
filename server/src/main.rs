mod ble;
mod config;
mod crypto;
mod error;
mod http;
mod misc;
mod state;

use std::sync::Arc;

use axum::{Router, routing};
use tokio::{net::TcpListener, signal};
use tower_http::trace::TraceLayer;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

use crate::{config::Config, error::AppError, state::WaiterManager};

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        eprintln!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> Result<(), AppError> {
    let mut config = Config::load().map_err(|e| AppError::Startup {
        message: format!("Failed to load configuration: {:#?}", e),
    })?;

    let mut filter = EnvFilter::from_default_env();

    for directive in &config.common.log_directives {
        filter = filter.add_directive(directive.parse().map_err(|e| AppError::Startup {
            message: format!("Invalid log directive '{}': {:#?}", directive, e),
        })?);
    }

    tracing_subscriber::registry()
        .with(filter)
        .with(tracing_subscriber::fmt::layer())
        .init();
    let waiter_manager = Arc::new(WaiterManager::new());

    let mut app = Router::new().route("/health", routing::get(|| async { "OK" }));

    if let Some(http_config) = config.http.take() {
        tracing::debug!("Enabled http service");
        let sub_router =
            http::router(&config.common, http_config.clone(), waiter_manager.clone()).await?;
        app = app.nest("/api/http", sub_router);

        // compatibility
        let sub_router = http::router(&config.common, http_config, waiter_manager.clone()).await?;
        app = app.nest("/api", sub_router);
    }

    if let Some(ble_config) = config.ble.take() {
        tracing::debug!("Enabled ble service");
        let sub_router = ble::router(&config.common, ble_config, waiter_manager.clone()).await?;
        app = app.nest("/api/ble", sub_router);
    }

    app = app.layer(TraceLayer::new_for_http());

    let listener = TcpListener::bind(&config.common.listen_addr)
        .await
        .map_err(|e| AppError::Startup {
            message: format!("Failed to bind to {}: {:#?}", config.common.listen_addr, e),
        })?;

    tracing::debug!("listening on {}", listener.local_addr().unwrap());

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .map_err(|e| AppError::Startup {
            message: format!("Server error: {:#?}", e),
        })?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }

    tracing::info!("signal received, starting graceful shutdown");
}
