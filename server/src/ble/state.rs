use std::sync::Arc;

use tokio::sync::Mutex;

use crate::{
    ble::{
        config::Config,
        services::{BleClientManager, BleRequestManager, BleServerManager},
    },
    config::CommonConfig,
};

pub struct AppBleState {
    pub common_config: CommonConfig,
    pub config: Config,
    pub request_manager: BleRequestManager,
    pub managers: Mutex<(BleClientManager, BleServerManager)>,
}

pub type SharedAppBleState = Arc<AppBleState>;
