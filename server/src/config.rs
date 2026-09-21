use std::path::PathBuf;

use clap::Parser;
use figment::{
    Error, Figment,
    providers::{Env, Format as _, Serialized, Toml},
};
use serde::{Deserialize, Serialize};

use crate::{ble::config::Config as BleConfig, http::config::Config as HttpConfig};

#[serde_with::skip_serializing_none]
#[derive(Parser, Debug, Serialize, Clone)]
#[command(author, version, about, long_about = None)]
pub struct CliArgs {
    /// Path to the configuration file
    #[arg(short, long, env = "OTP_PUSH_SERVER_CONFIG")]
    #[serde(skip)]
    config: Option<PathBuf>,

    /// Enable http otp push feature
    #[arg(long, default_missing_value = "true")]
    http_enabled: Option<bool>,

    /// Database URL
    #[arg(long)]
    database_url: Option<String>,

    /// JWT Secret
    #[arg(long)]
    jwt_secret: Option<String>,

    /// Google Client ID
    #[arg(long)]
    google_client_id: Option<String>,

    /// Listen address
    #[arg(long)]
    listen_addr: Option<String>,

    /// Path to FCM service account JSON
    #[arg(long)]
    fcm_service_account: Option<PathBuf>,

    /// Base URL for generating absolute links
    #[arg(long)]
    base_url: Option<String>,

    /// Days to retain OTP requests
    #[arg(long)]
    otp_request_retention_days: Option<i64>,

    /// Enable ble otp push feature
    #[arg(long, default_missing_value = "true")]
    ble_enabled: Option<bool>,
}

impl CliArgs {
    pub fn config(&self) -> Option<&PathBuf> {
        self.config.as_ref()
    }
}

#[derive(Debug, Deserialize, Serialize)]
pub struct Config {
    pub common: CommonConfig,
    #[serde(skip)]
    pub ble: Option<BleConfig>,
    #[serde(skip)]
    pub http: Option<HttpConfig>,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct CommonConfig {
    #[serde(default = "default_listen_addr")]
    pub listen_addr: String,
    pub base_url: Option<String>,
    #[serde(default)]
    pub log_directives: Vec<String>,
    pub ble_enabled: Option<bool>,
    pub http_enabled: Option<bool>,
}

impl Config {
    pub fn load() -> Result<Self, Box<Error>> {
        let args = CliArgs::parse();

        let mut figment = Figment::new();

        if let Some(config_path) = args.config() {
            figment = figment.merge(Toml::file(config_path)).focus("common");
        }

        figment = figment.merge(Env::prefixed("OTP_PUSH_SERVER_COMMON_"));

        // Finally, merge the CLI arguments themselves
        // We use serialized CLI args to figment
        figment = figment.merge(Serialized::defaults(&args));

        let common: CommonConfig = figment.extract().map_err(Box::from)?;

        let mut config = Config {
            common,
            ble: None,
            http: None,
        };

        if config.common.ble_enabled.unwrap_or(false) {
            config.ble = Some(BleConfig::load(&args)?);
        }

        if config.common.http_enabled.unwrap_or(true) {
            config.http = Some(HttpConfig::load(&args)?);
        }

        Ok(config)
    }
}

fn default_listen_addr() -> String {
    "127.0.0.1:3000".to_string()
}
