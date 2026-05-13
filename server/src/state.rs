use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use fcm_service::FcmService;
use google_oauth::AsyncClient;
use sea_orm::DatabaseConnection;
use tokio::sync::Notify;
use uuid::Uuid;

use crate::config::Config;

pub struct WaiterInfo {
    pub notify: Notify,
    pub waiter_count: AtomicUsize,
}

pub struct WaiterManager {
    waiters: Mutex<HashMap<Uuid, Arc<WaiterInfo>>>,
}

impl WaiterManager {
    pub fn new() -> Self {
        Self {
            waiters: Mutex::new(HashMap::new()),
        }
    }

    pub fn new_waiter(self: &Arc<Self>, request_id: Uuid) -> WaiterGuard {
        let mut lock = match self.waiters.lock() {
            Ok(lock) => lock,
            Err(_) => {
                tracing::error!("Failed to lock waiters map for request_id: {}", request_id);
                return WaiterGuard {
                    request_id,
                    info: None,
                    manager: Arc::clone(self),
                };
            }
        };

        let info = lock
            .entry(request_id)
            .or_insert_with(|| {
                Arc::new(WaiterInfo {
                    notify: Notify::new(),
                    waiter_count: AtomicUsize::new(0),
                })
            })
            .clone();

        info.waiter_count.fetch_add(1, Ordering::SeqCst);

        WaiterGuard {
            request_id,
            info: Some(info),
            manager: Arc::clone(self),
        }
    }

    pub fn notify_waiters(&self, request_id: Uuid) {
        let mut lock = match self.waiters.lock() {
            Ok(lock) => lock,
            Err(_) => {
                tracing::error!(
                    "Failed to lock waiters map for notification on request_id: {}",
                    request_id
                );
                return;
            }
        };

        if let Some(info) = lock.remove(&request_id) {
            info.notify.notify_waiters();
        }
    }

    fn release(&self, request_id: Uuid, info: &WaiterInfo) {
        if info.waiter_count.fetch_sub(1, Ordering::SeqCst) == 1 {
            let mut lock = match self.waiters.lock() {
                Ok(lock) => lock,
                Err(_) => {
                    tracing::error!(
                        "Failed to lock waiters map for release on request_id: {}",
                        request_id
                    );
                    return;
                }
            };
            if info.waiter_count.load(Ordering::SeqCst) == 0 {
                lock.remove(&request_id);
            }
        }
    }
}

pub struct WaiterGuard {
    request_id: Uuid,
    info: Option<Arc<WaiterInfo>>,
    manager: Arc<WaiterManager>,
}

impl WaiterGuard {
    pub async fn notified(&self, timeout: Duration) -> bool {
        if let Some(info) = &self.info {
            tokio::select! {
                _ = tokio::time::sleep(timeout) => false,
                _ = info.notify.notified() => true,
            }
        } else {
            tokio::time::sleep(timeout).await;
            true
        }
    }
}

impl Drop for WaiterGuard {
    fn drop(&mut self) {
        if let Some(info) = &self.info {
            self.manager.release(self.request_id, info);
        }
    }
}

pub struct AppState {
    pub db: DatabaseConnection,
    pub config: Config,
    pub waiter_manager: Arc<WaiterManager>,
    pub google_client: AsyncClient,
    pub fcm_service: Option<FcmService>,
}

pub type SharedState = Arc<AppState>;
