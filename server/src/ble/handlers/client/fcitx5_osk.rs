use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use common::Fcitx5OskHttpApiResponse;
use uuid::Uuid;

use crate::{
    ble::{error::AppBleError, handlers::client::BleCreateOtpRequest, state::SharedAppBleState},
    misc::fcitx5_osk,
};

pub async fn request(
    State(state): State<SharedAppBleState>,
    Json(request): Json<BleCreateOtpRequest>,
) -> Result<Json<Fcitx5OskHttpApiResponse>, AppBleError> {
    let pub_key = request
        .pub_key
        .map(|key| key.trim().to_string())
        .filter(|key| !key.is_empty());
    if let Some(pub_key) = &pub_key {
        super::validate_x509_public_key_pem(pub_key)?;
    }

    let request =
        state
            .request_manager
            .create_request(request.name, request.service_identifier, pub_key)?;

    let id_str = request.request_id.to_string();
    let short_id = if id_str.len() >= 6 {
        &id_str[id_str.len() - 6..]
    } else {
        &id_str
    };

    let prompts = vec![vec![(
        format!("Fill the request[#{short_id}] on your phone"),
        None,
    )]];

    let next_path = format!(
        "/api/ble/client/otp/fcitx5-osk/request/{}",
        request.request_id
    );
    let next = if let Some(base) = &state.common_config.base_url {
        Some(format!("{}{}", base.trim_end_matches('/'), next_path))
    } else {
        Some(format!(
            "http://{}{}",
            state.common_config.listen_addr, next_path
        ))
    };

    // request otp asynchronized
    tokio::spawn(super::send_request(state, request));

    Ok(Json(Fcitx5OskHttpApiResponse {
        prompts,
        groups: Vec::new(),
        secret: None,
        next,
    }))
}

pub async fn poll(
    State(state): State<SharedAppBleState>,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppBleError> {
    if let Some((encrypted, otp_code)) = state.request_manager.wait_for_otp(request_id).await? {
        let (secret, groups) = if encrypted {
            (Some(otp_code), vec![])
        } else {
            (None, fcitx5_osk::otp_to_groups(&otp_code))
        };
        Ok(Json(Fcitx5OskHttpApiResponse {
            prompts: Vec::new(),
            groups,
            secret,
            next: None,
        })
        .into_response())
    } else {
        Ok(StatusCode::NO_CONTENT.into_response())
    }
}
