use std::{sync::Arc, time::Duration};

use axum::{Router, routing};
use tokio::{sync::Mutex, time};

use crate::{
    ble::{
        config::Config,
        services::{BleClientManager, BleRequestManager, BleServerManager},
        state::AppBleState,
    },
    config::CommonConfig,
    error::AppError,
    state::WaiterManager,
};

pub mod config;
pub mod error;
mod handlers;
mod services;
mod state;

pub async fn router(
    common_config: &CommonConfig,
    config: Config,
    waiter_manager: Arc<WaiterManager>,
) -> Result<Router, AppError> {
    let request_manager = BleRequestManager::new(waiter_manager);
    let managers = Mutex::new((
        BleClientManager::new(&config)?,
        BleServerManager::new(&config)?,
    ));
    let state = Arc::new(AppBleState {
        common_config: common_config.clone(),
        config,
        request_manager,
        managers,
    });

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

    let router = Router::new()
        .nest("/client", api_client)
        .with_state(state.clone());

    // Background task for BLE request cleanup
    tokio::spawn(async move {
        let mut interval = time::interval(Duration::from_secs(60));
        loop {
            interval.tick().await;
            state.request_manager.cleanup_stale();
        }
    });

    Ok(router)
}
