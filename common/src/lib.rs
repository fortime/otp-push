use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub type DateTimeUtc = DateTime<Utc>;

#[derive(Debug, Serialize, Deserialize)]
pub struct AuthConfig {
    pub google_client_id: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct GoogleAuthRequest {
    pub id_token: String,
    pub device_id: Option<Uuid>,
    pub fcm_token: Option<String>,
    pub create_device: Option<bool>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct AuthResponse {
    pub access_token: String,
    pub user: UserDto,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct UserDto {
    pub id: Uuid,
    pub email: String,
    pub enabled: bool,
    pub admin: bool,
    pub token_version_at: DateTimeUtc,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpRecordDto {
    pub id: Uuid,
    pub name: String,
    pub service_identifier: String,
    pub created_at: DateTimeUtc,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateOtpRecordRequest {
    pub name: String,
    pub service_identifier: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ApiAccessTokenDto {
    pub id: Uuid,
    pub name: String,
    pub masked_token: String,
    pub last_used_at: Option<DateTimeUtc>,
    pub created_at: DateTimeUtc,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateTokenRequest {
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateTokenResponse {
    pub id: Uuid,
    pub name: String,
    pub token: String, // Only returned once upon creation
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateOtpRequest {
    pub pub_key: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpRequestResponse {
    pub request_id: Uuid,
    pub status: OtpRequestStatus,
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum OtpRequestStatus {
    Pending = 0,
    Completed = 1,
    Expired = 2,
    Failed = 3,
}

impl From<i32> for OtpRequestStatus {
    fn from(val: i32) -> Self {
        match val {
            1 => OtpRequestStatus::Completed,
            2 => OtpRequestStatus::Expired,
            3 => OtpRequestStatus::Failed,
            _ => OtpRequestStatus::Pending,
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PaginatedResponse<T> {
    pub items: Vec<T>,
    pub total: u64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpRequestDto {
    pub id: Uuid,
    pub otp_record_id: Uuid,
    pub otp_record_name: String,
    pub service_identifier: String,
    pub status: OtpRequestStatus,
    pub pub_key: Option<String>,
    pub created_at: DateTimeUtc,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpResponse {
    pub otp_code: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Fcitx5OskKeyValue {
    pub s: String,
    pub c: char,
    pub kc: i16,
    pub f: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Fcitx5OskComboKey {
    Key(Fcitx5OskKeyValue),
    Release,
    ReleaseAll,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Fcitx5OskComboKeyGroup {
    pub keys: Vec<Fcitx5OskComboKey>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Fcitx5OskHttpApiResponse {
    pub prompts: Vec<Vec<(String, Option<String>)>>,
    pub groups: Vec<Fcitx5OskComboKeyGroup>,
    pub secret: Option<String>,
    pub next: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SubmitOtpRequest {
    pub request_id: Uuid,
    pub otp_code: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct UpdateFcmTokenRequest {
    pub device_id: Uuid,
    pub fcm_token: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LogoutRequest {
    pub device_id: Uuid,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PaginationQuery {
    pub page: Option<u64>,
    pub limit: Option<u64>,
}
impl PaginationQuery {
    pub fn page(&self) -> u64 {
        self.page.unwrap_or(1).max(1)
    }

    pub fn limit(&self) -> u64 {
        let l = self.limit.unwrap_or(10);
        if [5, 10, 25, 50].contains(&l) { l } else { 10 }
    }

    pub fn offset(&self) -> u64 {
        (self.page() - 1) * self.limit()
    }
}
