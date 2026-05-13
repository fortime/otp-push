use std::collections::HashMap;
use std::time::Duration;

use chrono::{TimeDelta, Utc};
use common::OtpRequestStatus;
use fcm_service::{FcmMessage, FcmNotification, Target};
use sea_orm::{
    ActiveModelTrait, ActiveValue, ColumnTrait, DatabaseConnection, EntityTrait, QueryFilter,
};
use uuid::Uuid;

use crate::{
    entities::{api_access_token, device, otp_record, otp_request, user_device},
    error::AppError,
    state::SharedState,
};

pub async fn create_otp_request(
    state: &SharedState,
    token: &api_access_token::Model,
) -> Result<otp_request::Model, AppError> {
    let now = Utc::now();

    let new_request = otp_request::ActiveModel {
        id: ActiveValue::Set(Uuid::now_v7()),
        otp_record_id: ActiveValue::Set(token.otp_record_id),
        status: ActiveValue::Set(OtpRequestStatus::Pending as i32),
        created_at: ActiveValue::Set(now),
        updated_at: ActiveValue::Set(now),
        ..Default::default()
    };

    let result = new_request.insert(&state.db).await?;

    // Update last_used_at for the token
    let mut token_active: api_access_token::ActiveModel = token.clone().into();
    token_active.last_used_at = ActiveValue::Set(Some(now));
    token_active.updated_at = ActiveValue::Set(now);
    let _ = token_active.update(&state.db).await;

    // Send FCM notification
    if let Some(service) = &state.fcm_service {
        let record = otp_record::Entity::find_by_id(result.otp_record_id)
            .one(&state.db)
            .await?
            .ok_or(AppError::Internal {
                message: "Record missing for request".to_string(),
            })?;

        let devices = device::Entity::find()
            .inner_join(user_device::Entity)
            .filter(user_device::Column::UserId.eq(record.user_id))
            .all(&state.db)
            .await?;

        let id_str = result.id.to_string();
        let short_id = if id_str.len() >= 6 {
            &id_str[id_str.len() - 6..]
        } else {
            &id_str
        };

        for d in devices {
            let mut notification = FcmNotification::new();
            notification.set_title(format!("OTP Request (#{}): {}", short_id, record.name));
            notification.set_body(format!("New request for {}", record.service_identifier));

            let mut message = FcmMessage::new();
            message.set_notification(Some(notification));
            message.set_target(Target::Token(d.fcm_token));

            let mut data = HashMap::new();
            data.insert("request_id".to_string(), result.id.to_string());
            message.set_data(Some(data));

            if let Err(e) = service.send_notification(message).await {
                tracing::error!("Failed to send FCM notification to device: {}", e);
            }
        }
    }

    Ok(result)
}

pub async fn wait_for_otp(
    state: &SharedState,
    token: &api_access_token::Model,
    request_id: Uuid,
) -> Result<Option<String>, AppError> {
    let request = otp_request::Entity::find_by_id(request_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Request not found".to_string(),
        })?;

    if request.otp_record_id != token.otp_record_id {
        return Err(AppError::AuthError {
            message: "Forbidden".to_string(),
        });
    }

    if request.status == OtpRequestStatus::Completed as i32 {
        if let Some(code) = request.otp_code {
            return Ok(Some(code));
        }
    }

    let waiter = state.waiter_manager.new_waiter(request_id);

    if waiter.notified(Duration::from_secs(5)).await {
        let request = otp_request::Entity::find_by_id(request_id)
            .one(&state.db)
            .await?
            .ok_or(AppError::NotFound {
                message: "Request not found".to_string(),
            })?;

        if let Some(code) = request.otp_code {
            return Ok(Some(code));
        }
    }

    Ok(None)
}

pub async fn cleanup_old_requests(
    db: &DatabaseConnection,
    retention_days: i64,
) -> Result<u64, AppError> {
    let threshold = Utc::now() - TimeDelta::days(retention_days);

    let result = otp_request::Entity::delete_many()
        .filter(otp_request::Column::CreatedAt.lt(threshold))
        .exec(db)
        .await?;

    Ok(result.rows_affected)
}
