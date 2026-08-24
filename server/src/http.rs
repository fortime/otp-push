use std::{sync::Arc, time::Duration};

use axum::{Router, middleware, routing};
use fcm_service::FcmService;
use google_oauth::AsyncClient;
use sea_orm::Database;
use tokio::time;

use crate::{
    config::CommonConfig,
    error::AppError,
    http::{config::Config, services::otp, state::AppHttpState},
    state::WaiterManager,
};

mod auth;
pub mod config;
#[allow(unused_imports)]
mod entities;
pub mod error;
mod handlers;
mod services;
mod state;

pub async fn router(
    common_config: &CommonConfig,
    config: Config,
    waiter_manager: Arc<WaiterManager>,
) -> Result<Router, AppError> {
    let db = Database::connect(&config.database_url)
        .await
        .map_err(|e| AppError::Startup {
            message: format!("Failed to connect to database: {:#?}", e),
        })?;

    let fcm_service = if let Some(path) = &config.fcm_service_account {
        let path_str = path.to_str().ok_or(AppError::Startup {
            message: "Invalid FCM service account path".to_string(),
        })?;
        Some(FcmService::new(path_str))
    } else {
        None
    };

    let state = Arc::new(AppHttpState {
        common_config: common_config.clone(),
        db,
        google_client: AsyncClient::new(&config.google_client_id),
        config,
        waiter_manager,
        fcm_service,
    });

    // Public API (No User JWT Middleware)
    let api_public = Router::new()
        .route(
            "/auth/config",
            routing::get(handlers::auth::get_auth_config),
        )
        .route("/auth/google", routing::post(handlers::auth::auth_google));

    // Client API (API Token Auth handled in handlers, No User JWT Middleware)
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

    // User & Admin API (Requires User JWT Middleware)
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
            "/otp-records/:id",
            routing::delete(handlers::otp_records::delete_otp_record),
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

    let router = Router::new()
        .nest("/", api_public)
        .nest("/client", api_client)
        .nest("/", api_user)
        .with_state(state.clone());

    // Background task for OTP request cleanup
    tokio::spawn(async move {
        let mut interval = time::interval(Duration::from_secs(3600));
        loop {
            interval.tick().await;
            match otp::cleanup_old_requests(&state.db, state.config.otp_request_retention_days)
                .await
            {
                Ok(n) => tracing::info!("Clean {n} otp request[s]"),
                Err(e) => {
                    tracing::error!("Failed to cleanup old OTP requests: {:#?}", e);
                }
            }
        }
    });

    Ok(router)
}
