pub mod fcitx5_osk;

use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use common::{CreateOtpRequest, OtpRequestResponse, OtpRequestStatus, OtpResponse};
use spki::{
    SubjectPublicKeyInfoRef,
    der::{Decode, Document, pem::PemLabel},
};
use uuid::Uuid;

use crate::{
    auth::ApiTokenAuth, error::AppError, services::otp as otp_service, state::SharedState,
};

pub async fn request(
    State(state): State<SharedState>,
    auth: ApiTokenAuth,
    Json(payload): Json<CreateOtpRequest>,
) -> Result<Json<OtpRequestResponse>, AppError> {
    let pub_key = payload
        .pub_key
        .map(|key| key.trim().to_string())
        .filter(|key| !key.is_empty());
    if let Some(pub_key) = &pub_key {
        validate_x509_public_key_pem(pub_key)?;
    }

    let result = otp_service::create_otp_request(&state, &auth.token, pub_key).await?;

    Ok(Json(OtpRequestResponse {
        request_id: result.id,
        status: OtpRequestStatus::Pending,
    }))
}

pub(super) fn validate_x509_public_key_pem(pub_key: &str) -> Result<(), AppError> {
    let (label, document) = Document::from_pem(pub_key).map_err(|_| AppError::BadRequest {
        message: "pub_key must be a PEM-encoded X.509 SubjectPublicKeyInfo public key".to_string(),
    })?;
    if label != SubjectPublicKeyInfoRef::PEM_LABEL {
        return Err(AppError::BadRequest {
            message: "pub_key must be a PEM-encoded X.509 SubjectPublicKeyInfo public key"
                .to_string(),
        });
    }

    let public_key = SubjectPublicKeyInfoRef::from_der(document.as_bytes()).map_err(|_| {
        AppError::BadRequest {
            message: "pub_key must be a PEM-encoded X.509 SubjectPublicKeyInfo public key"
                .to_string(),
        }
    })?;

    let algorithm_oid = public_key.algorithm.oid.to_string();
    if algorithm_oid != "1.2.840.113549.1.1.1" && algorithm_oid != "1.2.840.10045.2.1" {
        return Err(AppError::BadRequest {
            message: "pub_key must use an RSA or EC public key algorithm for password encryption"
                .to_string(),
        });
    }

    Ok(())
}

pub async fn poll(
    State(state): State<SharedState>,
    auth: ApiTokenAuth,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppError> {
    let code = otp_service::wait_for_otp(&state, &auth.token, request_id).await?;

    if let Some((_, code)) = code {
        return Ok(Json(OtpResponse { otp_code: code }).into_response());
    }

    Ok(StatusCode::NO_CONTENT.into_response())
}
