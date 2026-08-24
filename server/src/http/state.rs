use std::sync::Arc;

use fcm_service::FcmService;
use google_oauth::AsyncClient;
use sea_orm::DatabaseConnection;

use crate::{config::CommonConfig, http::config::Config, state::WaiterManager};

pub struct AppHttpState {
    pub common_config: CommonConfig,
    pub db: DatabaseConnection,
    pub config: Config,
    pub waiter_manager: Arc<WaiterManager>,
    pub google_client: AsyncClient,
    pub fcm_service: Option<FcmService>,
}

pub type SharedAppHttpState = Arc<AppHttpState>;
