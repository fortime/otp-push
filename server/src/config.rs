use std::path::PathBuf;

use clap::Parser;
use figment::{
    Error, Figment,
    providers::{Env, Format, Serialized, Toml},
};
use serde::{Deserialize, Serialize};
use serde_with::skip_serializing_none;

#[skip_serializing_none]
#[derive(Parser, Debug, Serialize, Clone)]
#[command(author, version, about, long_about = None)]
struct CliArgs {
    /// Path to the configuration file
    #[arg(short, long, env = "OTP_PUSH_SERVER_CONFIG")]
    #[serde(skip)]
    config: Option<PathBuf>,

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
}

#[derive(Debug, Deserialize, Serialize)]
pub struct Config {
    pub database_url: String,
    pub jwt_secret: String,
    pub google_client_id: String,
    pub listen_addr: String,
    pub fcm_service_account: Option<PathBuf>,
    pub base_url: Option<String>,
    #[serde(default = "default_retention_days")]
    pub otp_request_retention_days: i64,
    #[serde(default)]
    pub log_directives: Vec<String>,
}

fn default_retention_days() -> i64 {
    7
}

impl Config {
    pub fn load() -> Result<Self, Error> {
        let args = CliArgs::parse();

        let mut figment = Figment::new();

        // If a config file is provided via CLI or ENV, merge it
        if let Some(config_path) = &args.config {
            figment = figment.merge(Toml::file(config_path));
        }

        figment = figment.merge(Env::prefixed("OTP_PUSH_SERVER_"));

        // Finally, merge the CLI arguments themselves
        // We use serialized CLI args to figment
        figment = figment.merge(Serialized::defaults(&args));

        figment.extract()
    }
}
