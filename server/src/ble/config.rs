use figment::{
    Error, Figment,
    providers::{Env, Format as _, Serialized, Toml},
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::config::CliArgs;

#[derive(Clone, Default, Debug, Deserialize, Serialize)]
pub struct BleServerConfig {
    #[serde(default = "default_server_gatt_service_uuid")]
    pub gatt_service_uuid: Uuid,
    #[serde(default = "default_server_gatt_request_char_uuid")]
    pub gatt_request_char_uuid: Uuid,
    #[serde(default = "default_server_gatt_response_char_uuid")]
    pub gatt_response_char_uuid: Uuid,
    #[serde(default = "default_server_gatt_ready_descriptor_uuid")]
    pub gatt_ready_descriptor_uuid: Uuid,
}

#[derive(Clone, Default, Debug, Deserialize, Serialize)]
pub struct BleClientConfig {
    #[serde(default = "default_client_gatt_service_uuid")]
    pub gatt_service_uuid: Uuid,
    #[serde(default = "default_client_gatt_request_char_uuid")]
    pub gatt_request_char_uuid: Uuid,
    #[serde(default = "default_client_gatt_response_char_uuid")]
    pub gatt_response_char_uuid: Uuid,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(tag = "type")]
pub enum BleMode {
    Server(BleServerConfig),
    Client(BleClientConfig),
}

impl Default for BleMode {
    fn default() -> Self {
        Self::Client(BleClientConfig {
            gatt_service_uuid: default_client_gatt_service_uuid(),
            gatt_request_char_uuid: default_client_gatt_request_char_uuid(),
            gatt_response_char_uuid: default_client_gatt_response_char_uuid(),
        })
    }
}

#[derive(Debug, Deserialize, Serialize)]
pub struct Config {
    #[serde(default = "default_scan_timeout_secs")]
    pub scan_timeout_secs: u64,
    #[serde(default = "default_read_timeout_secs")]
    pub read_timeout_secs: u64,
    #[serde(default)]
    pub device_whitelist: Vec<String>,
    #[serde(default)]
    pub ble_mode: BleMode,
}

fn default_scan_timeout_secs() -> u64 {
    30
}

fn default_read_timeout_secs() -> u64 {
    30
}

fn default_server_gatt_service_uuid() -> Uuid {
    Uuid::from_u128(0xa0000000_1034_49ce_abbe_8fb26e433894)
}

fn default_server_gatt_request_char_uuid() -> Uuid {
    Uuid::from_u128(0xa0100000_1034_49ce_abbe_8fb26e433894)
}

fn default_server_gatt_response_char_uuid() -> Uuid {
    Uuid::from_u128(0xa0100001_1034_49ce_abbe_8fb26e433894)
}

fn default_server_gatt_ready_descriptor_uuid() -> Uuid {
    Uuid::from_u128(0xa0010000_1034_49ce_abbe_8fb26e433894)
}

fn default_client_gatt_service_uuid() -> Uuid {
    Uuid::from_u128(0xa1000000_1034_49ce_abbe_8fb26e433894)
}

fn default_client_gatt_request_char_uuid() -> Uuid {
    Uuid::from_u128(0xa1100000_1034_49ce_abbe_8fb26e433894)
}

fn default_client_gatt_response_char_uuid() -> Uuid {
    Uuid::from_u128(0xa1100001_1034_49ce_abbe_8fb26e433894)
}

impl Config {
    pub fn load(args: &CliArgs) -> Result<Self, Box<Error>> {
        let mut figment = Figment::new();

        if let Some(config_path) = args.config() {
            figment = figment.merge(Toml::file(config_path)).focus("ble");
        }

        figment = figment.merge(Env::prefixed("OTP_PUSH_SERVER_BLE_"));

        // Finally, merge the CLI arguments themselves
        // We use serialized CLI args to figment
        figment = figment.merge(Serialized::defaults(&args));

        figment.extract().map_err(Box::from)
    }
}
