use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use chrono::Utc;
use common::{
    ApiAccessTokenDto, CreateOtpRecordRequest, CreateTokenRequest, CreateTokenResponse,
    OtpRecordDto, PaginatedResponse, PaginationQuery,
};
use sea_orm::{
    ActiveModelTrait, ActiveValue, ColumnTrait, EntityTrait, JoinType, ModelTrait, PaginatorTrait,
    QueryFilter, QueryOrder, QuerySelect, RelationTrait,
};
use uuid::Uuid;

use crate::{
    auth::AuthUser,
    entities::{api_access_token, otp_record},
    error::AppError,
    services::user::{self as user_service, DefaultUserLimit, UserLimitTrait},
    state::SharedState,
};

pub async fn list_otp_records(
    State(state): State<SharedState>,
    auth: AuthUser,
    pagination: Query<PaginationQuery>,
) -> Result<Json<PaginatedResponse<OtpRecordDto>>, AppError> {
    let limit = pagination.limit();
    let offset = pagination.offset();

    let selector = otp_record::Entity::find().filter(otp_record::Column::UserId.eq(auth.user.id));

    let total = selector.clone().count(&state.db).await?;

    let records = selector
        .order_by_desc(otp_record::Column::CreatedAt)
        .offset(offset)
        .limit(limit)
        .all(&state.db)
        .await?;

    let items = records
        .into_iter()
        .map(|r| OtpRecordDto {
            id: r.id,
            name: r.name,
            service_identifier: r.service_identifier,
            created_at: r.created_at,
        })
        .collect();

    Ok(Json(PaginatedResponse { items, total }))
}

pub async fn create_otp_record(
    State(state): State<SharedState>,
    auth: AuthUser,
    Json(payload): Json<CreateOtpRecordRequest>,
) -> Result<(StatusCode, Json<OtpRecordDto>), AppError> {
    // 1. Check existing records count
    let count = otp_record::Entity::find()
        .filter(otp_record::Column::UserId.eq(auth.user.id))
        .count(&state.db)
        .await?;

    // 2. Get user limit
    let limit = user_service::get_user_limits(&state.db, auth.user.id)
        .await?
        .map(|l| l.max_otp_records)
        .unwrap_or_else(|| DefaultUserLimit.max_otp_records());

    if count >= limit as u64 {
        return Err(AppError::LimitExceeded {
            message: format!("You have reached the limit of {} OTP records.", limit),
        });
    }

    let now = Utc::now();
    let new_record = otp_record::ActiveModel {
        id: ActiveValue::Set(Uuid::now_v7()),
        user_id: ActiveValue::Set(auth.user.id),
        name: ActiveValue::Set(payload.name),
        service_identifier: ActiveValue::Set(payload.service_identifier),
        created_at: ActiveValue::Set(now),
        updated_at: ActiveValue::Set(now),
    };

    let result = new_record.insert(&state.db).await?;

    let dto = OtpRecordDto {
        id: result.id,
        name: result.name,
        service_identifier: result.service_identifier,
        created_at: result.created_at,
    };

    Ok((StatusCode::CREATED, Json(dto)))
}

pub async fn create_api_token(
    State(state): State<SharedState>,
    auth: AuthUser,
    Path(record_id): Path<Uuid>,
    Json(payload): Json<CreateTokenRequest>,
) -> Result<(StatusCode, Json<CreateTokenResponse>), AppError> {
    // Verify ownership
    let record = otp_record::Entity::find_by_id(record_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Record not found".to_string(),
        })?;

    if record.user_id != auth.user.id {
        return Err(AppError::AuthError {
            message: "Forbidden".to_string(),
        });
    }

    // 1. Check existing tokens count for this USER (across all their records)
    let count = api_access_token::Entity::find()
        .join(
            JoinType::InnerJoin,
            api_access_token::Relation::OtpRecord.def(),
        )
        .filter(otp_record::Column::UserId.eq(auth.user.id))
        .count(&state.db)
        .await?;

    // 2. Get user limit
    let limit = user_service::get_user_limits(&state.db, auth.user.id)
        .await?
        .map(|l| l.max_tokens_per_record)
        .unwrap_or_else(|| DefaultUserLimit.max_tokens_per_record());

    if count >= limit as u64 {
        return Err(AppError::LimitExceeded {
            message: format!(
                "You have reached the limit of {} API tokens for this record.",
                limit
            ),
        });
    }

    let raw_token = Uuid::new_v4().to_string();
    let token_hash = raw_token.clone();
    let now = Utc::now();

    let new_token = api_access_token::ActiveModel {
        id: ActiveValue::Set(Uuid::now_v7()),
        otp_record_id: ActiveValue::Set(record_id),
        name: ActiveValue::Set(payload.name),
        token_hash: ActiveValue::Set(token_hash),
        created_at: ActiveValue::Set(now),
        updated_at: ActiveValue::Set(now),
        ..Default::default()
    };

    let result = new_token.insert(&state.db).await?;

    let dto = CreateTokenResponse {
        id: result.id,
        name: result.name,
        token: raw_token,
    };

    Ok((StatusCode::CREATED, Json(dto)))
}

pub async fn list_api_tokens(
    State(state): State<SharedState>,
    auth: AuthUser,
    Path(record_id): Path<Uuid>,
) -> Result<Json<Vec<ApiAccessTokenDto>>, AppError> {
    // Verify ownership of the record
    let record = otp_record::Entity::find_by_id(record_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Record not found".to_string(),
        })?;

    if record.user_id != auth.user.id {
        return Err(AppError::AuthError {
            message: "Forbidden".to_string(),
        });
    }

    let tokens = api_access_token::Entity::find()
        .filter(api_access_token::Column::OtpRecordId.eq(record_id))
        .all(&state.db)
        .await?;

    let dtos = tokens
        .into_iter()
        .map(|t| {
            let masked_token = if t.token_hash.len() < 13 {
                "*".repeat(13)
            } else {
                let len = t.token_hash.len();
                let first_part = &t.token_hash[..3];
                let last_part = &t.token_hash[len - 4..];
                let middle_stars_count = len - 3 - 4;
                format!(
                    "{}{}{}",
                    first_part,
                    "*".repeat(middle_stars_count),
                    last_part
                )
            };
            ApiAccessTokenDto {
                id: t.id,
                name: t.name,
                masked_token,
                last_used_at: t.last_used_at,
                created_at: t.created_at,
            }
        })
        .collect();

    Ok(Json(dtos))
}

pub async fn delete_api_token(
    State(state): State<SharedState>,
    auth: AuthUser,
    Path((record_id, token_id)): Path<(Uuid, Uuid)>,
) -> Result<StatusCode, AppError> {
    // Verify ownership of the record
    let record = otp_record::Entity::find_by_id(record_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Record not found".to_string(),
        })?;

    if record.user_id != auth.user.id {
        return Err(AppError::AuthError {
            message: "Forbidden".to_string(),
        });
    }

    let token = api_access_token::Entity::find_by_id(token_id)
        .one(&state.db)
        .await?
        .ok_or(AppError::NotFound {
            message: "Token not found".to_string(),
        })?;

    if token.otp_record_id != record_id {
        return Err(AppError::AuthError {
            message: "Token does not belong to this record".to_string(),
        });
    }

    token.delete(&state.db).await?;

    Ok(StatusCode::NO_CONTENT)
}
