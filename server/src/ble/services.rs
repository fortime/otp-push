use bluer::adv::Advertisement;
use bluer::gatt::CharacteristicWriter;
use bluer::gatt::local::{
    self, Application, Characteristic, CharacteristicControlEvent, CharacteristicNotify,
    CharacteristicNotifyMethod, CharacteristicRead, CharacteristicWrite, CharacteristicWriteMethod,
    Descriptor, DescriptorRead, ReqError, Service as LocalService,
};
use bluer::gatt::remote::Service as RemoteService;
use bluer::{
    Adapter, AdapterEvent, AdapterProperty, Address, Device, DiscoveryFilter, DiscoveryTransport,
    Error as BluerError, Session, SessionEvent,
};
use chrono::Utc;
use futures::{FutureExt as _, StreamExt};
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{self, Cursor};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};
use tokio::sync::oneshot::{self, Receiver, Sender};
use tokio::sync::watch::{self, Sender as WatchSender};
use tokio::task::JoinHandle;
use tokio::time;
use uuid::Uuid;

use crate::ble::config::{BleClientConfig, BleMode, BleServerConfig, Config};
use crate::ble::error::AppBleError;
use crate::state::WaiterManager;
use common::{BleOtpRequest, BleOtpResponse, BleOtpResponseBody, DateTimeUtc};

const HEADER_LEN: usize = 4;

#[derive(Clone)]
pub struct BleRequestInfo {
    pub encrypted: bool,
    pub response: Option<BleOtpResponse>,
    pub created_at: DateTimeUtc,
}

pub struct BleRequestManager {
    requests: Mutex<HashMap<Uuid, BleRequestInfo>>,
    waiter_manager: Arc<WaiterManager>,
}

impl BleRequestManager {
    pub fn new(waiter_manager: Arc<WaiterManager>) -> Self {
        Self {
            requests: Mutex::new(HashMap::new()),
            waiter_manager,
        }
    }

    fn requests(&self) -> Result<MutexGuard<'_, HashMap<Uuid, BleRequestInfo>>, AppBleError> {
        self.requests.lock().map_err(|_| AppBleError::Internal {
            message: "requests is poisoned".to_string(),
        })
    }

    pub fn create_request(
        &self,
        name: String,
        service_identifier: String,
        pub_key: Option<String>,
    ) -> Result<BleOtpRequest, AppBleError> {
        let request_id = Uuid::new_v4();
        let request = BleOtpRequest {
            name,
            service_identifier,
            request_id,
            pub_key,
        };
        let mut lock = self.requests()?;
        lock.insert(
            request_id,
            BleRequestInfo {
                encrypted: request.pub_key.is_some(),
                response: None,
                created_at: Utc::now(),
            },
        );
        Ok(request)
    }

    pub fn request_info(&self, request_id: Uuid) -> Option<BleRequestInfo> {
        if let Ok(lock) = self.requests() {
            lock.get(&request_id).cloned()
        } else {
            None
        }
    }

    pub fn fulfill_request(&self, request_id: Uuid, response: BleOtpResponse) -> bool {
        if let Ok(mut lock) = self.requests() {
            if let Some(info) = lock.get_mut(&request_id) {
                info.response = Some(response);
                self.waiter_manager.notify_waiters(request_id);
                true
            } else {
                false
            }
        } else {
            tracing::warn!(
                "requests is poisoned, response of request[{}] is ignored",
                request_id
            );
            false
        }
    }

    pub async fn wait_for_otp(
        &self,
        request_id: Uuid,
    ) -> Result<Option<(bool, String)>, AppBleError> {
        // Check if response is already available
        if let Ok(lock) = self.requests()
            && let Some(info) = lock.get(&request_id)
        {
            let BleRequestInfo {
                encrypted,
                response,
                ..
            } = info.clone();
            match response.map(|r| r.body) {
                Some(BleOtpResponseBody::Ok { otp_code }) => {
                    return Ok(Some((encrypted, otp_code)));
                }
                Some(BleOtpResponseBody::Err { message }) => {
                    return Err(AppBleError::BadGateway { message });
                }
                None => {}
            }
        }

        // Otherwise, register a waiter and sleep
        let guard = self.waiter_manager.new_waiter(request_id);
        let duration = Duration::from_secs(5);

        if guard.notified(duration).await
            && let Ok(lock) = self.requests()
            && let Some(info) = lock.get(&request_id)
        {
            let BleRequestInfo {
                encrypted,
                response,
                ..
            } = info.clone();
            match response.map(|r| r.body) {
                Some(BleOtpResponseBody::Ok { otp_code }) => {
                    return Ok(Some((encrypted, otp_code)));
                }
                Some(BleOtpResponseBody::Err { message }) => {
                    return Err(AppBleError::BadGateway { message });
                }
                None => {}
            }
        }
        Ok(None)
    }

    pub fn cleanup_stale(&self) {
        if let Ok(mut lock) = self.requests() {
            let now = Utc::now();
            lock.retain(|_, info| now.signed_duration_since(info.created_at).num_minutes() < 5);
        }
    }
}

pub struct BleClientManager {
    last_device: Option<(Device, String)>,
    device_whitelist: HashSet<Address>,
    scan_timeout: Duration,
    read_timeout: Duration,
    config: BleClientConfig,
}

impl BleClientManager {
    pub fn new(config: &Config) -> Result<Self, AppBleError> {
        let manager = Self {
            last_device: None,
            device_whitelist: config
                .device_whitelist
                .iter()
                .map(|addr| addr.parse())
                .collect::<Result<HashSet<Address>, _>>()
                .map_err(|e| AppBleError::Internal {
                    message: format!("Invalid address: {e}"),
                })?,
            scan_timeout: Duration::from_secs(config.scan_timeout_secs),
            read_timeout: Duration::from_secs(config.read_timeout_secs),
            config: match &config.ble_mode {
                BleMode::Server(_) => Default::default(),
                BleMode::Client(ble_client_config) => ble_client_config.clone(),
            },
        };

        Ok(manager)
    }

    pub async fn discover_device(&mut self) -> Result<Option<Device>, AppBleError> {
        let session = Session::new().await?;
        let adapter = session.default_adapter().await?;
        if !adapter.is_powered().await? {
            tracing::warn!("Default adapter[{}] is not power on", adapter.name());
            return Ok(None);
        }

        // filter by gatt service uuid
        adapter
            .set_discovery_filter(DiscoveryFilter {
                uuids: [self.config.gatt_service_uuid].into(),
                transport: DiscoveryTransport::Le,
                ..Default::default()
            })
            .await?;
        let mut discovery = adapter.discover_devices().await?;

        let timeout_fut = time::sleep(self.scan_timeout);
        tokio::pin!(timeout_fut);
        loop {
            tokio::select! {
                _ = &mut timeout_fut => {
                    tracing::warn!("Scan timeout reached, restarting scan...");
                    return Ok(None);
                }
                Some(event) = discovery.next() => {
                    if let AdapterEvent::DeviceAdded(addr) = event {
                        if self.device_whitelist.is_empty()
                            || self.device_whitelist.contains(&addr) {
                                // connect and check if service_uuid is supported
                                let device = match adapter.device(addr) {
                                    Ok(d) => d,
                                    Err(e) => {
                                        tracing::error!("Invalid device adress[{addr}]: {e:?}");
                                        continue;
                                    },
                                };
                                let name = device.alias().await.unwrap_or_else(|_| "Unknown".to_string());
                                tracing::info!("Found whitelisted device: {name}/{addr}");
                                let connect_res: Result<(), BluerError> = async {
                                    if !device.is_connected().await? {
                                        device.connect().await?;
                                    }
                                    Ok(())
                                }.await;
                                if let Err(e) = connect_res {
                                    tracing::error!("Failed to connect to device[{name}]: {e:?}");
                                    continue;
                                }
                                // DiscoveryFilter doesn't guarantee the result. We check again
                                match find_service(&device, &self.config.gatt_service_uuid).await {
                                    Ok(Some(_)) => {
                                        tracing::info!("Found device with service supported: {name}");
                                        return Ok(Some(device));
                                    },
                                    Ok(None) => {
                                        tracing::info!("Device[{name}] has no ble otp push support");
                                    }
                                    Err(e) => {
                                        tracing::warn!("Unable to find service: {e:?}");
                                    },
                                }
                            } else {
                                tracing::info!("Ignore device: {}", addr);
                        }
                    }
                }
            }
        }
    }

    pub async fn send(&mut self, request: BleOtpRequest) -> Result<BleOtpResponse, AppBleError> {
        loop {
            let device = if let Some((last_device, name)) = self.last_device.as_ref() {
                match last_device.is_connected().await {
                    Ok(true) => {}
                    Ok(false) => {
                        tracing::info!("Device[{name}/{}] is disconnected", last_device.address());
                        self.last_device.take();
                        continue;
                    }
                    Err(e) => {
                        tracing::error!(
                            "Unable to get the connection state of last known device[{name}/{}]: {e:?}",
                            last_device.address()
                        );
                        if let Err(e) = last_device.disconnect().await {
                            tracing::error!(
                                "Disconnect device[{name}/{}] failed: {e:?}",
                                last_device.address()
                            );
                        }
                        self.last_device.take();
                        continue;
                    }
                }
                last_device
            } else {
                let Some(device) = self.discover_device().await? else {
                    return Err(AppBleError::Internal {
                        message: "No available device".to_string(),
                    });
                };
                let name = device.alias().await?;
                self.last_device = Some((device, name));
                &self
                    .last_device
                    .as_ref()
                    .expect("Last device should exist")
                    .0
            };

            return send_request(device, &self.config, request, self.read_timeout).await;
        }
    }
}

type BleOtpRequestWithSender = (BleOtpRequest, Sender<Result<BleOtpResponse, AppBleError>>);

enum BleServerManagerEvent {
    Request(BleOtpRequestWithSender),
    RecoverRequest(BleOtpRequestWithSender),
    AdapterAdded(Adapter),
    AdapterRemoved,
    AdapterPowered(Adapter, bool),
    Exit,
}

enum BleServerEvent {
    Request(BleOtpRequestWithSender),
    ClientReady(Address),
    ResponseReady,
}

struct BleServerRequestContext {
    current_request_tx: WatchSender<Option<Vec<u8>>>,
    responses: HashMap<Address, Receiver<BleOtpResponse>>,
    writers: HashMap<Address, (CharacteristicWriter, bool)>,
    pending_requests: VecDeque<BleOtpRequestWithSender>,
    sent_devices: HashSet<Address>,
    start_time: Option<Instant>,
}

impl BleServerRequestContext {
    pub fn new(current_request_tx: WatchSender<Option<Vec<u8>>>) -> Self {
        Self {
            current_request_tx,
            responses: Default::default(),
            writers: Default::default(),
            pending_requests: Default::default(),
            sent_devices: Default::default(),
            start_time: Default::default(),
        }
    }

    fn reset(&mut self) -> Result<(), AppBleError> {
        // clear
        self.responses.clear();
        self.sent_devices.clear();
        self.start_time.take();
        self.current_request_tx
            .send(None)
            .map_err(|_| AppBleError::Internal {
                message: "Unabled to update current request".to_string(),
            })?;
        Ok(())
    }
}

struct BleServerBackground {
    read_timeout: Duration,
    config: BleServerConfig,
    device_whitelist: HashSet<Address>,
    ble_server_tx: UnboundedSender<BleServerEvent>,
    ble_server_rx: UnboundedReceiver<BleServerEvent>,
    ble_server_manager_tx: UnboundedSender<BleServerManagerEvent>,
    adapter: Adapter,
}

impl BleServerBackground {
    async fn run(&mut self) -> Result<(), AppBleError> {
        let advertisement = Advertisement {
            service_uuids: [self.config.gatt_service_uuid].into_iter().collect(),
            local_name: Some("Otp Push Gatt Server".to_string()),
            discoverable: Some(true),
            ..Default::default()
        };

        let _adv_handle = self.adapter.advertise(advertisement).await?;
        tracing::info!(
            "BLE advertisement[{}] started",
            self.config.gatt_service_uuid
        );

        let (current_request_tx, current_request_rx) = watch::channel(Option::<Vec<u8>>::None);
        let (mut request_char_control, request_char_control_handle) =
            local::characteristic_control();
        let (mut response_char_control, response_char_control_handle) =
            local::characteristic_control();
        let service = LocalService {
            uuid: self.config.gatt_service_uuid,
            primary: true,

            characteristics: vec![
                Characteristic {
                    uuid: self.config.gatt_request_char_uuid,
                    read: Some(CharacteristicRead {
                        read: true,
                        encrypt_read: true,
                        encrypt_authenticated_read: true,
                        fun: Box::new({
                            let device_whitelist = self.device_whitelist.clone();
                            move |req| {
                                let address = req.device_address;
                                tracing::debug!(
                                    "A read request from device[{address}], offset: {}",
                                    req.offset
                                );
                                let allow = device_whitelist.is_empty()
                                    || device_whitelist.contains(&address);
                                let mut current_request_rx = current_request_rx.clone();
                                async move {
                                    if !allow {
                                        tracing::warn!("Address[{address}] isn't in the whitelist, reject the read request");
                                        return Ok(vec![]);
                                    }
                                    loop {
                                        {
                                            let data = current_request_rx.borrow();
                                            if let Some(data) = data.as_ref() {
                                                if req.offset as usize > data.len() {
                                                    return Err(ReqError::InvalidOffset);
                                                }
                                                return Ok(data[req.offset as usize..].to_vec());
                                            }
                                        }
                                        if current_request_rx.changed().await.is_err() {
                                            tracing::error!("Unabled to wait the change of current request");
                                            return Ok("{}".as_bytes().to_vec());
                                        }
                                    }
                                }
                                .boxed()
                            }
                        }),
                        ..Default::default()
                    }),
                    notify: Some(CharacteristicNotify {
                        notify: true,
                        method: CharacteristicNotifyMethod::Io,
                        ..Default::default()
                    }),
                    descriptors: vec![Descriptor {
                        // The android may be not ready to receive the notification, so we only mark
                        // the client as ready if it reads this descriptor
                        uuid: self.config.gatt_ready_descriptor_uuid,
                        read: Some(DescriptorRead {
                            read: true,
                            fun: Box::new({
                                let ble_server_tx = self.ble_server_tx.clone();
                                let device_whitelist = self.device_whitelist.clone();
                                move |req| {
                                    let address = req.device_address;
                                    tracing::debug!(
                                        "A descriptor read request from device[{address}], offset: {}",
                                        req.offset
                                    );
                                    let allow = device_whitelist.is_empty()
                                        || device_whitelist.contains(&address);
                                    let ble_server_tx = ble_server_tx.clone();
                                    async move {
                                        if req.offset > 0 {
                                            return Err(ReqError::InvalidOffset);
                                        }
                                        if allow {
                                            tracing::info!("Setting device[{address}] to be ready");
                                            let _ = ble_server_tx
                                                .send(BleServerEvent::ClientReady(address));
                                        }
                                        Ok(vec![b'\0'])
                                    }
                                    .boxed()
                                }
                            }),
                            ..Default::default()
                        }),
                        ..Default::default()
                    }],
                    control_handle: request_char_control_handle,
                    ..Default::default()
                },
                Characteristic {
                    uuid: self.config.gatt_response_char_uuid,
                    write: Some(CharacteristicWrite {
                        write: true,
                        reliable_write: true,
                        encrypt_write: true,
                        encrypt_authenticated_write: true,
                        method: CharacteristicWriteMethod::Io,
                        ..Default::default()
                    }),
                    control_handle: response_char_control_handle,
                    ..Default::default()
                },
            ],

            ..Default::default()
        };

        let application = Application {
            services: vec![service],
            ..Default::default()
        };

        let _gatt_handle = self.adapter.serve_gatt_application(application).await?;

        let mut context = BleServerRequestContext::new(current_request_tx);
        loop {
            tokio::select! {
                Some(request_char_event) = request_char_control.next() => {
                    match request_char_event {
                        CharacteristicControlEvent::Notify(writer) => {
                            let address = writer.device_address();
                            if self.device_whitelist.is_empty() || self.device_whitelist.contains(&address) {
                                tracing::debug!("Add device[{address}] for notification");
                                context.writers.insert(address, (writer, false));
                            } else {
                                tracing::warn!("Address[{address}] isn't in the whitelist, reject the request char connection");
                            }
                        }
                        _ => {
                            tracing::warn!("Unexpected char event, expect Notify, but Write");
                        }
                    }
                }
                Some(response_char_event) = response_char_control.next() => {
                    match response_char_event {
                        CharacteristicControlEvent::Write(request) => {
                            let address = request.device_address();
                            tracing::debug!("A response write request from device[{address}]");
                            if context.writers.contains_key(&address) {
                                let mut reader = match request.accept() {
                                    Ok(reader) => reader,
                                    Err(e) => {
                                        tracing::warn!("Can't accept write request: {e:?}");
                                        continue;
                                    }
                                };
                                let (tx, rx) = oneshot::channel();
                                let ble_server_tx = self.ble_server_tx.clone();
                                let read_timeout = self.read_timeout;
                                tokio::spawn(async move {
                                    match read_response(&mut reader, read_timeout).await {
                                        Ok(r) => {
                                            // other devices has sent the response
                                            let _ = tx.send(r);
                                            let _ = ble_server_tx.send(BleServerEvent::ResponseReady);
                                        }
                                        Err(e) => {
                                            tracing::warn!("Failed to get response from {address}: {e:?}");
                                        },
                                    }
                                });
                                context.responses.insert(address, rx);
                            } else {
                                tracing::warn!("Address[{address}] isn't in the writers, reject the response char connection");
                                request.reject(local::ReqError::NotPermitted);
                            }
                        },
                        _ => {
                            tracing::warn!("Unexpected char event, expect Write, but Notify");
                        }
                    }
                }
                ble_server_event = self.ble_server_rx.recv() => {
                    match ble_server_event {
                        Some(BleServerEvent::Request(request)) => {
                            context.pending_requests.push_back(request);
                        }
                        Some(BleServerEvent::ResponseReady) => {
                        }
                        Some(BleServerEvent::ClientReady(address)) => {
                            if let Some(writer) = context.writers.get_mut(&address) {
                                tracing::info!("Device[{address}] is ready");
                                writer.1 = true;
                            }
                        }
                        None => {
                            // send the pending requests back
                            while let Some(request) = context.pending_requests.pop_back() {
                                if self.ble_server_manager_tx.send(BleServerManagerEvent::RecoverRequest(request)).is_err() {
                                    tracing::warn!("Ble server manager background terminated, drop request");
                                }
                            }
                            return Ok(());
                        }
                    }
                }
                else => {
                    tracing::warn!("One of the char ends, remove the adapter");
                    let _ = self.ble_server_manager_tx.send(BleServerManagerEvent::AdapterRemoved);
                    return Ok(());
                }
            }

            if context.sent_devices.is_empty() {
                // cleanup stale requests only if the first request is sent
                cleanup_stale_requests(&mut context.pending_requests);
            }

            while let Some(request) = context.pending_requests.pop_front() {
                if context.sent_devices.is_empty() {
                    let mut buf = Vec::with_capacity(128);
                    let mut cursor = Cursor::new(&mut buf);
                    cursor.write_u32_le(0).await.map_err(|e| {
                        tracing::error!("Unable to write to cursor: {e:?}");
                        AppBleError::Internal {
                            message: "Unable to write to cursor".to_string(),
                        }
                    })?;
                    match serde_json::to_writer(&mut cursor, &request.0) {
                        Ok(()) => {
                            tracing::info!(
                                "BLE Request[{}]: ({} bytes)...",
                                request.0.request_id,
                                buf.len()
                            );
                            let len = buf.len() as u32 - HEADER_LEN as u32;
                            buf[0..HEADER_LEN].copy_from_slice(&len.to_be_bytes());
                            context.current_request_tx.send(Some(buf)).map_err(|_| {
                                AppBleError::Internal {
                                    message: "Unabled to update current request".to_string(),
                                }
                            })?;
                        }
                        Err(e) => {
                            tracing::error!(
                                "Failed to serialize BLE request: {:?}, Drop request[{}]",
                                e,
                                request.0.request_id
                            );
                            continue;
                        }
                    }
                } else if request.1.is_closed() {
                    context.reset()?;
                    continue;
                }

                let mut closeds = vec![];
                for (address, writer) in &mut context.writers {
                    match writer.0.is_closed() {
                        Ok(false) => {}
                        Ok(true) => {
                            tracing::info!("Device[{address}] disconnected");
                            closeds.push(*address);
                            continue;
                        }
                        Err(e) => {
                            tracing::info!(
                                "Failed to detect if device[{address}] disconnected: {e:?}, treat it as disconnected"
                            );
                            closeds.push(*address);
                            continue;
                        }
                    }
                    if !writer.1 {
                        // not ready
                        continue;
                    }
                    if context.sent_devices.insert(*address) {
                        // notify request id
                        if let Err(e) = async {
                            writer.0.write_all(request.0.request_id.as_bytes()).await?;
                            writer.0.flush().await
                        }
                        .await
                        {
                            tracing::error!(
                                "Failed to write request to BLE device[{address}]: {:?}",
                                e
                            );
                        } else {
                            tracing::debug!(
                                "Notify device[{address}] for request[{}]",
                                request.0.request_id
                            );
                            if context.start_time.is_none() {
                                context.start_time = Some(Instant::now());
                            }
                        }
                    }
                }
                for closed in closeds {
                    context.writers.remove(&closed);
                }

                // try read response
                closeds = vec![];
                let mut response = None;
                for (address, rx) in &mut context.responses {
                    // read from sent_devices only
                    if context.sent_devices.contains(address)
                        && let Ok(r) = rx.try_recv()
                    {
                        if r.request_id != request.0.request_id {
                            tracing::error!(
                                "BLE Response for {} instead of {}",
                                r.request_id,
                                request.0.request_id
                            );
                            closeds.push(*address);
                        } else {
                            response = Some(r);
                            break;
                        }
                    }
                }

                // request is finished, clear
                let response = if let Some(response) = response {
                    Some(Ok(response))
                } else if let Some(start_time) = context.start_time
                    && start_time.elapsed() > self.read_timeout
                {
                    Some(Err(AppBleError::BadGateway {
                        message: "Failed to read response from BLE device: timeout".to_string(),
                    }))
                } else {
                    None
                };
                if let Some(response) = response {
                    context.reset()?;
                    if request.1.send(response).is_err() {
                        tracing::error!(
                            "Unable to send the response of request[{}]",
                            request.0.request_id
                        );
                    };
                } else {
                    for closed in closeds {
                        context.responses.remove(&closed);
                    }
                    context.pending_requests.push_front(request);
                    break;
                }
            }
        }
    }

    fn start(mut self) -> JoinHandle<()> {
        tokio::spawn(async move {
            while let Err(e) = self.run().await {
                tracing::error!("Ble server background run failed: {e:?}, sleep 30s");
                time::sleep(Duration::from_secs(30)).await;
            }
        })
    }
}

struct BleServer {
    ble_server_tx: UnboundedSender<BleServerEvent>,
    adapter_name: String,
    bg_handle: JoinHandle<()>,
}

impl BleServer {
    async fn new(
        read_timeout: Duration,
        config: &BleServerConfig,
        device_whitelist: &HashSet<Address>,
        ble_server_manager_tx: UnboundedSender<BleServerManagerEvent>,
        adapter: Adapter,
    ) -> Result<Self, AppBleError> {
        let adapter_name = adapter.alias().await?;
        tracing::info!("Starting ble server with adapter[{adapter_name}]");
        let (ble_server_tx, ble_server_rx) = mpsc::unbounded_channel();
        let bg_handle = BleServerBackground {
            read_timeout,
            config: config.clone(),
            device_whitelist: device_whitelist.clone(),
            ble_server_tx: ble_server_tx.clone(),
            ble_server_rx,
            ble_server_manager_tx,
            adapter: adapter.clone(),
        }
        .start();
        Ok(Self {
            ble_server_tx,
            adapter_name,
            bg_handle,
        })
    }

    fn send_request(
        &self,
        request: BleOtpRequestWithSender,
    ) -> Result<(), BleOtpRequestWithSender> {
        self.ble_server_tx
            .send(BleServerEvent::Request(request))
            .map_err(|e| {
                #[allow(irrefutable_let_patterns)]
                let BleServerEvent::Request(request) = e.0 else {
                    unreachable!("Unexpected BleServerEvent variant")
                };
                request
            })
    }
}

impl Drop for BleServer {
    fn drop(&mut self) {
        self.bg_handle.abort();
        tracing::info!("Destroying ble server, adapter: {}", self.adapter_name);
    }
}

struct BleServerManagerBackground {
    read_timeout: Duration,
    config: BleServerConfig,
    device_whitelist: HashSet<Address>,
    tx: UnboundedSender<BleServerManagerEvent>,
    rx: UnboundedReceiver<BleServerManagerEvent>,
}

impl BleServerManagerBackground {
    fn new(
        config: &Config,
        tx: UnboundedSender<BleServerManagerEvent>,
        rx: UnboundedReceiver<BleServerManagerEvent>,
    ) -> Result<Self, AppBleError> {
        let device_whitelist = config
            .device_whitelist
            .iter()
            .map(|addr| addr.parse())
            .collect::<Result<HashSet<Address>, _>>()
            .map_err(|e| AppBleError::Internal {
                message: format!("Invalid address: {e}"),
            })?;
        Ok(Self {
            read_timeout: Duration::from_secs(config.read_timeout_secs),
            config: match &config.ble_mode {
                BleMode::Server(ble_server_config) => ble_server_config.clone(),
                BleMode::Client(_) => Default::default(),
            },
            device_whitelist,
            tx,
            rx,
        })
    }

    async fn run(&mut self) -> Result<(), AppBleError> {
        tokio::spawn(find_available_adapter(self.tx.clone()));

        let mut pending_requests = VecDeque::new();
        let mut ble_server = None;
        while let Some(event) = self.rx.recv().await {
            match event {
                BleServerManagerEvent::Request(request) => {
                    pending_requests.push_back(request);
                }
                BleServerManagerEvent::RecoverRequest(request) => {
                    pending_requests.push_front(request);
                }
                BleServerManagerEvent::AdapterAdded(adapter) => {
                    tokio::spawn({
                        let tx = self.tx.clone();
                        async move {
                            if let Err(e) = monitor_adapter(&tx, adapter).await {
                                tracing::error!("Monitor adapter failed: {e:?}");
                                let _ = tx.send(BleServerManagerEvent::AdapterRemoved);
                            }
                        }
                    });
                }
                BleServerManagerEvent::AdapterRemoved => {
                    tokio::spawn(find_available_adapter(self.tx.clone()));
                }
                BleServerManagerEvent::AdapterPowered(adapter, powered) => {
                    if powered {
                        ble_server = Some(
                            BleServer::new(
                                self.read_timeout,
                                &self.config,
                                &self.device_whitelist,
                                self.tx.clone(),
                                adapter,
                            )
                            .await?,
                        );
                    } else {
                        ble_server.take();
                    }
                }
                BleServerManagerEvent::Exit => break,
            }

            cleanup_stale_requests(&mut pending_requests);

            // handle request
            if let Some(ble_server) = &ble_server {
                while let Some(request) = pending_requests.pop_front() {
                    if let Err(request) = ble_server.send_request(request) {
                        tracing::warn!("Ble server background terminated");
                        pending_requests.push_front(request);
                        break;
                    }
                }
            }
        }

        tracing::info!("BleServer exits");
        Ok(())
    }

    fn start(mut self) -> JoinHandle<()> {
        tokio::spawn(async move {
            while let Err(e) = self.run().await {
                tracing::error!("Ble server manager background run failed: {e:?}, sleep 30s");
                time::sleep(Duration::from_secs(30)).await;
            }
        })
    }
}

pub struct BleServerManager {
    read_timeout: Duration,
    tx: UnboundedSender<BleServerManagerEvent>,
}

impl BleServerManager {
    pub fn new(config: &Config) -> Result<Self, AppBleError> {
        let (tx, rx) = mpsc::unbounded_channel();
        if let BleMode::Server(_) = config.ble_mode {
            BleServerManagerBackground::new(config, tx.clone(), rx)?.start();
        }
        Ok(Self {
            read_timeout: Duration::from_secs(config.read_timeout_secs),
            tx,
        })
    }

    pub async fn send(&mut self, request: BleOtpRequest) -> Result<BleOtpResponse, AppBleError> {
        let (tx, rx) = oneshot::channel();
        self.tx
            .send(BleServerManagerEvent::Request((request, tx)))
            .map_err(|_| AppBleError::Internal {
                message: "Ble server channel is closed".to_string(),
            })?;
        tokio::select! {
            // Add additional 500ms so internal background has more time to handle timeout
            _ = time::sleep(self.read_timeout + Duration::from_millis(500)) => {
                tracing::warn!("Read timeout reached");
                Err(AppBleError::Internal {
                    message: "Failed to read response from BLE device: timeout".to_string(),
                })
            },
            response = rx => {
                response.map_err(|_| AppBleError::Internal {
                    message: "Failed to receive the response".to_string(),
                })?
            }
        }
    }
}

impl Drop for BleServerManager {
    fn drop(&mut self) {
        // tell `BleServer` to exit
        let _ = self.tx.send(BleServerManagerEvent::Exit);
    }
}

fn send_ble_server_manager_event(
    tx: &UnboundedSender<BleServerManagerEvent>,
    event: BleServerManagerEvent,
) -> Result<(), AppBleError> {
    tx.send(event).map_err(|_| AppBleError::Internal {
        message: "Unable to send ble server event".to_string(),
    })
}

fn cleanup_stale_requests(pending_requests: &mut VecDeque<BleOtpRequestWithSender>) {
    // remove stale requests
    while let Some(request) = pending_requests.front() {
        if !request.1.is_closed() {
            break;
        }
        tracing::warn!("Drop request: {:?}", request.0);
        pending_requests.pop_front();
    }
}

async fn find_available_adapter(tx: UnboundedSender<BleServerManagerEvent>) {
    async fn _find_available_adapter(
        tx: &UnboundedSender<BleServerManagerEvent>,
    ) -> Result<(), AppBleError> {
        let session = Session::new().await?;
        match session.default_adapter().await {
            Ok(a) => {
                let _ = tx.send(BleServerManagerEvent::AdapterAdded(a));
                return Ok(());
            }
            Err(e) => {
                tracing::warn!("Unable to find available adapter: {e:?}");
            }
        };

        let events = session.events().await?;
        futures::pin_mut!(events);
        while let Some(event) = events.next().await {
            if let SessionEvent::AdapterAdded(n) = event {
                let _ = tx.send(BleServerManagerEvent::AdapterAdded(session.adapter(&n)?));
                return Ok(());
            }
        }
        Err(AppBleError::Internal {
            message: "Session events ended".to_string(),
        })
    }

    loop {
        if let Err(e) = _find_available_adapter(&tx).await {
            tracing::error!("find available adapter failed: {e:?}, sleep 30s then try again");
            time::sleep(Duration::from_secs(30)).await;
        } else {
            return;
        }
    }
}

async fn monitor_adapter(
    tx: &UnboundedSender<BleServerManagerEvent>,
    adapter: Adapter,
) -> Result<(), AppBleError> {
    let name = adapter.alias().await?;
    tracing::info!("Monitoring adapter[{name}]");
    let mut events = adapter.events().await?;

    send_ble_server_manager_event(
        tx,
        BleServerManagerEvent::AdapterPowered(adapter.clone(), adapter.is_powered().await?),
    )?;
    loop {
        if let Some(event) = events.next().await {
            if let AdapterEvent::PropertyChanged(AdapterProperty::Powered(p)) = event {
                tracing::info!("Adapter[{name}] powered: {p}");

                send_ble_server_manager_event(
                    tx,
                    BleServerManagerEvent::AdapterPowered(adapter.clone(), p),
                )?;
            }
        } else {
            tracing::warn!("Adapter[{name}] has been removed");
            send_ble_server_manager_event(tx, BleServerManagerEvent::AdapterRemoved)?;
            return Ok(());
        }
    }
}

async fn write_framed_msg<W: AsyncWrite + Unpin>(writer: &mut W, data: &[u8]) -> io::Result<()> {
    let len = data.len() as u32;
    writer.write_all(&len.to_be_bytes()).await?;
    writer.write_all(data).await?;
    writer.flush().await?;
    Ok(())
}

async fn read_framed_msg<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<Vec<u8>> {
    let mut len_bytes = [0u8; HEADER_LEN];
    reader.read_exact(&mut len_bytes).await?;
    let len = u32::from_be_bytes(len_bytes) as usize;
    if len > 0x8_0000 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Framed message too large",
        ));
    }
    let mut buf = vec![0u8; len];
    reader.read_exact(&mut buf).await?;
    Ok(buf)
}

async fn read_response<R: AsyncRead + Unpin>(
    reader: &mut R,
    read_timeout: Duration,
) -> Result<BleOtpResponse, AppBleError> {
    let buf = tokio::select! {
        _ = time::sleep(read_timeout) => {
            tracing::warn!("Read timeout reached");
            return Err(AppBleError::BadGateway {
                message: "Failed to read response from BLE device: timeout".to_string(),
            });
        }
        res = read_framed_msg(reader) => {
            match res {
                Ok(b) => b,
                Err(e) => {
                    tracing::error!("Failed to read response from BLE device: {:?}", e);
                    return Err(AppBleError::BadGateway {
                        message: "Failed to read response from BLE device".to_string(),
                    });
                }
            }
        }
    };

    match serde_json::from_slice::<BleOtpResponse>(&buf) {
        Ok(response) => {
            tracing::info!(
                "Received BLE Response for request ID: {}",
                response.request_id
            );
            Ok(response)
        }
        Err(e) => {
            tracing::error!("Failed to parse BLE Response: {:?}", e);
            Err(AppBleError::BadGateway {
                message: "Failed to parse BLE Response".to_string(),
            })
        }
    }
}

async fn find_service(
    device: &Device,
    gatt_service_uuid: &Uuid,
) -> Result<Option<RemoteService>, AppBleError> {
    let services = device.services().await?;

    tracing::debug!("Searching service: {gatt_service_uuid}");

    for service in services {
        let uuid = service.uuid().await?;
        tracing::debug!("Found service: {uuid}");
        if uuid == *gatt_service_uuid {
            return Ok(Some(service));
        }
    }
    Ok(None)
}

async fn send_request(
    device: &Device,
    config: &BleClientConfig,
    request: BleOtpRequest,
    read_timeout: Duration,
) -> Result<BleOtpResponse, AppBleError> {
    tracing::debug!(
        "Discovering service[{}] on device[{}/{}]...",
        config.gatt_service_uuid,
        device.adapter_name(),
        device.address()
    );

    let service = match find_service(device, &config.gatt_service_uuid).await? {
        Some(s) => s,
        None => {
            return Err(AppBleError::Internal {
                message: "Target GATT service not found on device".into(),
            });
        }
    };

    tracing::debug!("Found target service, discovering characteristics...");
    let chars = service.characteristics().await?;
    let mut request_char = None;
    let mut response_char = None;

    for c in chars {
        let uuid = c.uuid().await?;
        if uuid == config.gatt_request_char_uuid {
            request_char = Some(c);
        } else if uuid == config.gatt_response_char_uuid {
            response_char = Some(c);
        }
    }

    let (request_char, response_char) = match (request_char, response_char) {
        (Some(req), Some(resp)) => (req, resp),
        _ => {
            return Err(AppBleError::Internal {
                message: "Required characteristics (Request/Response) not found".to_string(),
            });
        }
    };

    tracing::debug!("Obtaining write IO on Request characteristic...");
    let mut writer = request_char.write_io().await?;
    tracing::info!("Obtained write IO with MTU = {}", writer.mtu());

    let serialized = match serde_json::to_vec(&request) {
        Ok(bytes) => bytes,
        Err(e) => {
            tracing::error!("Failed to serialize BLE request: {:?}", e);
            return Err(AppBleError::Internal {
                message: "Failed to serialize BLE request".to_string(),
            });
        }
    };

    tracing::info!(
        "Writing BLE Request for ID: {} ({} bytes)...",
        request.request_id,
        serialized.len()
    );
    if let Err(e) = write_framed_msg(&mut writer, &serialized).await {
        tracing::error!("Failed to write request to BLE device: {:?}", e);
        return Err(AppBleError::Internal {
            message: "Failed to write request to BLE device".to_string(),
        });
    }

    tracing::debug!("Obtaining response on Response characteristic...");
    let data = response_char.read().await?;

    match read_response(&mut Cursor::new(data), read_timeout).await {
        Ok(response) => {
            if response.request_id != request.request_id {
                tracing::error!(
                    "BLE Response for {} instead of {}",
                    response.request_id,
                    request.request_id
                );
                Err(AppBleError::BadGateway {
                    message: "The request_id doesn't match, we may encounter a malware phone"
                        .to_string(),
                })
            } else {
                Ok(response)
            }
        }
        Err(e) => Err(e),
    }
}
