use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use common::{CreateOtpRequest, Fcitx5OskHttpApiResponse};
use uuid::Uuid;

use crate::{
    http::{
        auth::ApiTokenAuth, error::AppHttpError, services::otp as otp_service,
        state::SharedAppHttpState,
    },
    misc::fcitx5_osk,
};

pub async fn request(
    State(state): State<SharedAppHttpState>,
    auth: ApiTokenAuth,
    Json(request): Json<CreateOtpRequest>,
) -> Result<Json<Fcitx5OskHttpApiResponse>, AppHttpError> {
    let pub_key = request
        .pub_key
        .map(|key| key.trim().to_string())
        .filter(|key| !key.is_empty());
    if let Some(pub_key) = &pub_key {
        super::validate_x509_public_key_pem(pub_key)?;
    }

    let result = otp_service::create_otp_request(&state, &auth.token, pub_key).await?;

    let id_str = result.id.to_string();
    let short_id = if id_str.len() >= 6 {
        &id_str[id_str.len() - 6..]
    } else {
        &id_str
    };

    let prompts = vec![vec![(
        format!("Fill the request[#{short_id}] on your phone"),
        None,
    )]];

    let next_path = format!("/api/http/client/otp/fcitx5-osk/request/{}", result.id);
    let next = if let Some(base) = &state.common_config.base_url {
        Some(format!("{}{}", base.trim_end_matches('/'), next_path))
    } else {
        Some(format!(
            "http://{}{}",
            state.common_config.listen_addr, next_path
        ))
    };

    Ok(Json(Fcitx5OskHttpApiResponse {
        prompts,
        groups: Vec::new(),
        secret: None,
        next,
    }))
}

pub async fn poll(
    State(state): State<SharedAppHttpState>,
    auth: ApiTokenAuth,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppHttpError> {
    let code = otp_service::wait_for_otp(&state, &auth.token, request_id).await?;

    if let Some((encrypted, code)) = code {
        let (secret, groups) = if encrypted {
            (Some(code), vec![])
        } else {
            (None, fcitx5_osk::otp_to_groups(&code))
        };
        return Ok(Json(Fcitx5OskHttpApiResponse {
            prompts: Vec::new(),
            groups,
            secret,
            next: None,
        })
        .into_response());
    }

    Ok(StatusCode::NO_CONTENT.into_response())
}
