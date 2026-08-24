use std::path::PathBuf;

use figment::{
    Error, Figment,
    providers::{Env, Format, Serialized, Toml},
};
use serde::{Deserialize, Serialize};

use crate::config::CliArgs;

// Clone for compatibility
#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct Config {
    pub database_url: String,
    pub jwt_secret: String,
    pub google_client_id: String,
    pub fcm_service_account: Option<PathBuf>,
    #[serde(default = "default_retention_days")]
    pub otp_request_retention_days: i64,
    #[serde(default)]
    pub ble_enabled: bool,
}

fn default_retention_days() -> i64 {
    7
}

impl Config {
    pub fn load(args: &CliArgs) -> Result<Self, Box<Error>> {
        let mut figment = Figment::new();

        // If a config file is provided via CLI or ENV, merge it
        if let Some(config_path) = args.config() {
            figment = figment.merge(Toml::file(config_path)).focus("http");
        }

        figment = figment.merge(Env::prefixed("OTP_PUSH_SERVER_HTTP_"));

        // Finally, merge the CLI arguments themselves
        // We use serialized CLI args to figment
        figment = figment.merge(Serialized::defaults(&args));

        figment.extract().map_err(Box::from)
    }
}
