use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use chrono::Utc;
use common::UserDto;
use sea_orm::{ActiveValue, IntoActiveModel, entity::prelude::*};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::AdminAuthUser,
    entities::{user, user_limit},
    error::AppError,
    services::user::{self as user_service, DefaultUserLimit, UserLimitTrait},
    state::SharedState,
};

#[derive(Serialize)]
pub struct UserLimitsDto {
    pub max_otp_records: i32,
    pub max_tokens_per_record: i32,
}

impl<T: UserLimitTrait> From<T> for UserLimitsDto {
    fn from(value: T) -> Self {
        Self {
            max_otp_records: value.max_otp_records(),
            max_tokens_per_record: value.max_tokens_per_record(),
        }
    }
}

pub async fn list_users(
    State(state): State<SharedState>,
    _admin: AdminAuthUser,
) -> Result<Json<Vec<UserDto>>, AppError> {
    let users = user::Entity::find().all(&state.db).await?;
    let dtos = users
        .into_iter()
        .map(|u| UserDto {
            id: u.id,
            email: u.email,
            enabled: u.enabled,
            admin: u.admin,
            token_version_at: u.token_version_at,
        })
        .collect();

    Ok(Json(dtos))
}

pub async fn get_user_limits(
    State(state): State<SharedState>,
    _admin: AdminAuthUser,
    Path(user_id): Path<Uuid>,
) -> Result<Json<UserLimitsDto>, AppError> {
    let limit = user_service::get_user_limits(&state.db, user_id)
        .await?
        .map(UserLimitsDto::from)
        .unwrap_or_else(|| DefaultUserLimit.into());

    Ok(Json(limit))
}

pub async fn toggle_user_enabled(
    State(state): State<SharedState>,
    admin: AdminAuthUser,
    Path(user_id): Path<Uuid>,
    Json(payload): Json<bool>,
) -> Result<StatusCode, AppError> {
    if admin.0.user.id == user_id {
        return Err(AppError::SelfModificationForbidden);
    }
    let user = user::Entity::find_by_id(user_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "User not found".to_string(),
        })?;

    let mut user_active: user::ActiveModel = user.into();
    user_active.enabled = ActiveValue::Set(payload);
    user_active.updated_at = ActiveValue::Set(Utc::now());
    user_active.update(&state.db).await?;

    Ok(StatusCode::OK)
}

pub async fn toggle_user_admin(
    State(state): State<SharedState>,
    admin: AdminAuthUser,
    Path(user_id): Path<Uuid>,
    Json(payload): Json<bool>,
) -> Result<StatusCode, AppError> {
    if admin.0.user.id == user_id {
        return Err(AppError::SelfModificationForbidden);
    }
    let user = user::Entity::find_by_id(user_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "User not found".to_string(),
        })?;

    let mut user_active: user::ActiveModel = user.into();
    user_active.admin = ActiveValue::Set(payload);
    user_active.updated_at = ActiveValue::Set(Utc::now());
    user_active.update(&state.db).await?;

    Ok(StatusCode::OK)
}

#[derive(Deserialize)]
pub struct UpdateLimitsRequest {
    pub max_otp_records: i32,
    pub max_tokens_per_record: i32,
}

pub async fn update_user_limits(
    State(state): State<SharedState>,
    _admin: AdminAuthUser,
    Path(user_id): Path<Uuid>,
    Json(payload): Json<UpdateLimitsRequest>,
) -> Result<StatusCode, AppError> {
    let now = Utc::now();
    let mut limit_active = match user_service::get_user_limits(&state.db, user_id).await? {
        Some(l) => l.into_active_model(),
        None => user_limit::ActiveModel {
            id: ActiveValue::Set(Uuid::now_v7()),
            user_id: ActiveValue::Set(user_id),
            created_at: ActiveValue::Set(now),
            ..Default::default()
        },
    };

    limit_active.max_otp_records = ActiveValue::Set(payload.max_otp_records);
    limit_active.max_tokens_per_record = ActiveValue::Set(payload.max_tokens_per_record);
    limit_active.updated_at = ActiveValue::Set(now);

    if limit_active.id.is_set() {
        limit_active.insert(&state.db).await?;
    } else {
        limit_active.update(&state.db).await?;
    }

    Ok(StatusCode::OK)
}
