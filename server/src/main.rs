mod auth;
mod config;
#[allow(unused_imports)]
mod entities;
mod error;
mod handlers;
mod services;
mod state;

use std::{sync::Arc, time::Duration};

use axum::{Router, middleware, routing};
use fcm_service::FcmService;
use google_oauth::AsyncClient;
use sea_orm::Database;
use tokio::{net::TcpListener, time};
use tower_http::trace::TraceLayer;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

use crate::{config::Config, error::AppError, services::otp, state::AppState};

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        eprintln!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> Result<(), AppError> {
    let config = Config::load().map_err(|e| AppError::StartupError {
        message: format!("Failed to load configuration: {:#?}", e),
    })?;

    let mut filter = EnvFilter::from_default_env();

    for directive in &config.log_directives {
        filter = filter.add_directive(directive.parse().map_err(|e| AppError::StartupError {
            message: format!("Invalid log directive '{}': {:#?}", directive, e),
        })?);
    }

    tracing_subscriber::registry()
        .with(filter)
        .with(tracing_subscriber::fmt::layer())
        .init();

    let db = Database::connect(&config.database_url)
        .await
        .map_err(|e| AppError::StartupError {
            message: format!("Failed to connect to database: {:#?}", e),
        })?;

    let fcm_service = if let Some(path) = &config.fcm_service_account {
        let path_str = path.to_str().ok_or(AppError::StartupError {
            message: "Invalid FCM service account path".to_string(),
        })?;
        Some(FcmService::new(path_str))
    } else {
        None
    };

    let state = Arc::new(AppState {
        db,
        google_client: AsyncClient::new(&config.google_client_id),
        config,
        waiter_manager: Arc::new(crate::state::WaiterManager::new()),
        fcm_service,
    });

    // 1. Public API (No User JWT Middleware)
    let api_public = Router::new()
        .route(
            "/auth/config",
            routing::get(handlers::auth::get_auth_config),
        )
        .route("/auth/google", routing::post(handlers::auth::auth_google));

    // 2. Client API (API Token Auth handled in handlers, No User JWT Middleware)
    let api_client = Router::new()
        .route("/otp/request", routing::post(handlers::client::request))
        .route("/otp/request/:id", routing::get(handlers::client::poll))
        .route(
            "/otp/fcitx5-osk/request",
            routing::post(handlers::client::fcitx5_osk::request),
        )
        .route(
            "/otp/fcitx5-osk/request/:id",
            routing::get(handlers::client::fcitx5_osk::poll),
        );

    // 3. User & Admin API (Requires User JWT Middleware)
    let api_user = Router::new()
        .route(
            "/auth/refresh",
            routing::post(handlers::auth::refresh_token),
        )
        .route(
            "/otp-records",
            routing::get(handlers::otp_records::list_otp_records),
        )
        .route(
            "/otp-records",
            routing::post(handlers::otp_records::create_otp_record),
        )
        .route(
            "/otp-records/:id/tokens",
            routing::get(handlers::otp_records::list_api_tokens),
        )
        .route(
            "/otp-records/:id/tokens",
            routing::post(handlers::otp_records::create_api_token),
        )
        .route(
            "/otp-records/:id/tokens/:token_id",
            routing::delete(handlers::otp_records::delete_api_token),
        )
        .route(
            "/mobile/requests",
            routing::get(handlers::mobile::list_pending_requests),
        )
        .route(
            "/mobile/requests/:id",
            routing::get(handlers::mobile::get_request),
        )
        .route(
            "/mobile/otp/submit",
            routing::post(handlers::mobile::submit_otp),
        )
        .route(
            "/mobile/fcm-token",
            routing::put(handlers::mobile::update_fcm_token),
        )
        .route("/mobile/logout", routing::delete(handlers::mobile::logout))
        .route("/users/me", routing::get(handlers::user::get_me))
        .route("/admin/users", routing::get(handlers::admin::list_users))
        .route(
            "/admin/users/:id/enable",
            routing::put(handlers::admin::toggle_user_enabled),
        )
        .route(
            "/admin/users/:id/admin",
            routing::put(handlers::admin::toggle_user_admin),
        )
        .route(
            "/admin/users/:id/limits",
            routing::get(handlers::admin::get_user_limits),
        )
        .route(
            "/admin/users/:id/limits",
            routing::put(handlers::admin::update_user_limits),
        )
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth::auth_middleware,
        ));

    let app = Router::new()
        .route("/health", routing::get(|| async { "OK" }))
        .nest("/api", api_public)
        .nest("/api/client", api_client)
        .nest("/api", api_user)
        .layer(TraceLayer::new_for_http())
        .with_state(state.clone());

    let listener = TcpListener::bind(&state.config.listen_addr)
        .await
        .map_err(|e| AppError::StartupError {
            message: format!("Failed to bind to {}: {:#?}", state.config.listen_addr, e),
        })?;

    tracing::debug!("listening on {}", listener.local_addr().unwrap());

    // Background task for OTP request cleanup
    let db_for_cleanup = state.db.clone();
    let retention_days = state.config.otp_request_retention_days;
    tokio::spawn(async move {
        let mut interval = time::interval(Duration::from_secs(3600));
        loop {
            interval.tick().await;
            match otp::cleanup_old_requests(&db_for_cleanup, retention_days).await {
                Ok(n) => tracing::info!("Clean {n} otp request[s]"),
                Err(e) => {
                    tracing::error!("Failed to cleanup old OTP requests: {:#?}", e);
                }
            }
        }
    });

    axum::serve(listener, app)
        .await
        .map_err(|e| AppError::StartupError {
            message: format!("Server error: {:#?}", e),
        })?;

    Ok(())
}
