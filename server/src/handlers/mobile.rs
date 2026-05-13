use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use chrono::{Duration, Utc};
use common::{
    LogoutRequest, OtpRequestDto, OtpRequestStatus, PaginatedResponse, PaginationQuery,
    SubmitOtpRequest, UpdateFcmTokenRequest,
};
use sea_orm::{ActiveValue, QueryOrder, QuerySelect, entity::prelude::*};
use uuid::Uuid;

use crate::{
    auth::AuthUser,
    entities::{device, otp_record, otp_request, user_device},
    error::AppError,
    state::SharedState,
};

pub async fn list_pending_requests(
    State(state): State<SharedState>,
    auth: AuthUser,
    pagination: Query<PaginationQuery>,
) -> Result<Json<PaginatedResponse<OtpRequestDto>>, AppError> {
    let limit = pagination.limit();
    let offset = pagination.offset();
    let thirty_minutes_ago = Utc::now() - Duration::minutes(30);

    let selector = otp_request::Entity::find()
        .inner_join(otp_record::Entity)
        .filter(otp_record::Column::UserId.eq(auth.user.id))
        .filter(otp_request::Column::Status.eq(OtpRequestStatus::Pending as i32))
        .filter(otp_request::Column::CreatedAt.gte(thirty_minutes_ago));

    let total = selector.clone().count(&state.db).await?;

    let requests = selector
        .order_by_desc(otp_request::Column::CreatedAt)
        .offset(offset)
        .limit(limit)
        .all(&state.db)
        .await?;

    let mut items = Vec::new();
    for r in requests {
        let record = otp_record::Entity::find_by_id(r.otp_record_id)
            .one(&state.db)
            .await?
            .ok_or(AppError::Internal {
                message: "Record missing for request".to_string(),
            })?;

        items.push(OtpRequestDto {
            id: r.id,
            otp_record_id: r.otp_record_id,
            otp_record_name: record.name,
            service_identifier: record.service_identifier,
            status: r.status.into(),
            created_at: r.created_at,
        });
    }

    Ok(Json(PaginatedResponse { items, total }))
}

pub async fn submit_otp(
    State(state): State<SharedState>,
    _auth: AuthUser,
    Json(payload): Json<SubmitOtpRequest>,
) -> Result<StatusCode, AppError> {
    let request = otp_request::Entity::find_by_id(payload.request_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Request not found".to_string(),
        })?;

    let mut request_active: otp_request::ActiveModel = request.into();
    request_active.otp_code = ActiveValue::Set(Some(payload.otp_code));
    request_active.status = ActiveValue::Set(OtpRequestStatus::Completed as i32);
    request_active.updated_at = ActiveValue::Set(Utc::now());

    request_active.update(&state.db).await?;

    state.waiter_manager.notify_waiters(payload.request_id);

    Ok(StatusCode::OK)
}

pub async fn update_fcm_token(
    State(state): State<SharedState>,
    auth: AuthUser,
    Json(payload): Json<UpdateFcmTokenRequest>,
) -> Result<StatusCode, AppError> {
    let db = &state.db;

    let binding = user_device::Entity::find()
        .filter(user_device::Column::UserId.eq(auth.user.id))
        .inner_join(device::Entity)
        .filter(device::Column::DeviceUuid.eq(payload.device_id))
        .one(db)
        .await?
        .ok_or_else(|| AppError::NotFound {
            message: "Device not found or not bound to user".to_string(),
        })?;

    let device_record = device::Entity::find_by_id(binding.device_id)
        .one(db)
        .await?
        .ok_or_else(|| AppError::Internal {
            message: "Device record missing".to_string(),
        })?;

    if device_record.fcm_token != payload.fcm_token {
        let mut active_device: device::ActiveModel = device_record.into();
        active_device.fcm_token = ActiveValue::Set(payload.fcm_token);
        active_device.updated_at = ActiveValue::Set(Utc::now());
        active_device.update(db).await?;
    }

    Ok(StatusCode::OK)
}

pub async fn get_request(
    State(state): State<SharedState>,
    auth: AuthUser,
    Path(request_id): Path<Uuid>,
) -> Result<Json<OtpRequestDto>, AppError> {
    let db = &state.db;

    let request = otp_request::Entity::find_by_id(request_id)
        .one(db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Request not found".to_string(),
        })?;

    let record = otp_record::Entity::find_by_id(request.otp_record_id)
        .one(db)
        .await?
        .ok_or(AppError::Internal {
            message: "Record missing for request".to_string(),
        })?;

    if record.user_id != auth.user.id {
        return Err(AppError::NotFound {
            message: "Request not found".to_string(),
        });
    }

    Ok(Json(OtpRequestDto {
        id: request.id,
        otp_record_id: request.otp_record_id,
        otp_record_name: record.name,
        service_identifier: record.service_identifier,
        status: request.status.into(),
        created_at: request.created_at,
    }))
}

pub async fn logout(
    State(state): State<SharedState>,
    auth: AuthUser,
    Json(payload): Json<LogoutRequest>,
) -> Result<StatusCode, AppError> {
    let db = &state.db;

    let binding = user_device::Entity::find()
        .filter(user_device::Column::UserId.eq(auth.user.id))
        .inner_join(device::Entity)
        .filter(device::Column::DeviceUuid.eq(payload.device_id))
        .one(db)
        .await?;

    if let Some(binding) = binding {
        user_device::Entity::delete_by_id(binding.id)
            .exec(db)
            .await?;
    }

    Ok(StatusCode::OK)
}
