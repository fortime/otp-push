use axum::{Json, extract::State, response::IntoResponse};
use chrono::Utc;
use common::{AuthConfig, AuthResponse, GoogleAuthRequest, UserDto};
use sea_orm::{
    ActiveModelTrait, ActiveValue, ColumnTrait, DbErr, EntityTrait, QueryFilter, QueryOrder,
    QuerySelect, TransactionTrait, sea_query::OnConflict,
};
use uuid::Uuid;

use crate::{
    auth,
    entities::{device, user, user_device},
    error::AppError,
    state::SharedState,
};

pub async fn get_auth_config(State(state): State<SharedState>) -> Json<AuthConfig> {
    Json(AuthConfig {
        google_client_id: state.config.google_client_id.clone(),
    })
}

pub async fn auth_google(
    State(state): State<SharedState>,
    Json(payload): Json<GoogleAuthRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    let google_payload = state
        .google_client
        .validate_id_token(payload.id_token)
        .await
        .map_err(|e| AppError::GoogleAuthError {
            message: e.to_string(),
        })?;

    let google_email = google_payload
        .email
        .ok_or_else(|| AppError::GoogleAuthError {
            message: "Google token did not contain an email".to_string(),
        })?;

    let existing_user = user::Entity::find()
        .filter(user::Column::Email.eq(google_email.clone()))
        .one(&state.db)
        .await?;

    let user = match existing_user {
        Some(u) => u,
        None => {
            // Create new DISABLED user
            let now = Utc::now();
            let new_user = user::ActiveModel {
                id: ActiveValue::Set(Uuid::now_v7()),
                email: ActiveValue::Set(google_email),
                enabled: ActiveValue::Set(false), // DISABLED BY DEFAULT
                token_version_at: ActiveValue::Set(now),
                created_at: ActiveValue::Set(now),
                updated_at: ActiveValue::Set(now),
                ..Default::default()
            };
            new_user.insert(&state.db).await?
        }
    };

    if let Some(device_uuid) = payload.device_id {
        let db = &state.db;
        db.transaction::<_, (), AppError>(|txn| {
            let user_id = user.id;
            let fcm_token = payload.fcm_token.clone();
            let create_device = payload.create_device.unwrap_or(false);
            Box::pin(async move {
                // 1. Handle Device record
                let device_record = if create_device {
                    let fcm = fcm_token.clone().ok_or_else(|| AppError::AuthError {
                        message: "fcm_token is required when create_device is true".to_string(),
                    })?;
                    let new_device = device::ActiveModel {
                        id: ActiveValue::Set(Uuid::now_v7()),
                        device_uuid: ActiveValue::Set(device_uuid),
                        fcm_token: ActiveValue::Set(fcm),
                        created_at: ActiveValue::Set(Utc::now()),
                        updated_at: ActiveValue::Set(Utc::now()),
                    };
                    match device::Entity::insert(new_device)
                        .on_conflict(
                            OnConflict::column(device::Column::DeviceUuid)
                                .do_nothing()
                                .to_owned(),
                        )
                        .exec_with_returning(txn)
                        .await
                    {
                        Ok(d) => d,
                        Err(DbErr::RecordNotInserted) => {
                            return Err(AppError::DeviceConflict {
                                message: "Device UUID already exists".to_string(),
                            });
                        }
                        Err(e) => return Err(e.into()),
                    }
                } else {
                    // Find existing device
                    device::Entity::find()
                        .filter(device::Column::DeviceUuid.eq(device_uuid))
                        .one(txn)
                        .await?
                        .ok_or_else(|| AppError::NotFound {
                            message: "Device not found".to_string(),
                        })?
                };

                // 2. Handle Binding (UserDevice)
                // Check if device is bound to another user
                let existing_binding = user_device::Entity::find()
                    .filter(user_device::Column::DeviceId.eq(device_record.id))
                    .one(txn)
                    .await?;

                if let Some(binding) = existing_binding {
                    if binding.user_id != user_id {
                        return Err(AppError::DeviceConflict {
                            message: "Device is bound to another user".to_string(),
                        });
                    }
                    // Update updated_at of existing binding
                    let mut active_binding: user_device::ActiveModel = binding.into();
                    active_binding.updated_at = ActiveValue::Set(Utc::now());
                    active_binding.update(txn).await?;
                } else {
                    // Create new binding
                    let new_binding = user_device::ActiveModel {
                        id: ActiveValue::Set(Uuid::now_v7()),
                        user_id: ActiveValue::Set(user_id),
                        device_id: ActiveValue::Set(device_record.id),
                        created_at: ActiveValue::Set(Utc::now()),
                        updated_at: ActiveValue::Set(Utc::now()),
                    };
                    new_binding.insert(txn).await?;

                    // 3. Enforce 3-device limit
                    let mut bindings = user_device::Entity::find()
                        .filter(user_device::Column::UserId.eq(user_id))
                        .order_by_asc(user_device::Column::UpdatedAt)
                        .select_only()
                        .column(user_device::Column::Id)
                        .into_tuple::<(Uuid,)>()
                        .all(txn)
                        .await?;

                    bindings.truncate(bindings.len().saturating_sub(3));

                    if !bindings.is_empty() {
                        user_device::Entity::delete_many()
                            .filter(user_device::Column::Id.is_in(bindings.iter().map(|b| b.0)))
                            .exec(txn)
                            .await?;
                        tracing::info!(
                            "Removed out-of-limit older user device bindings: {bindings:?}"
                        );
                    }
                }

                // 4. Update FCM token (only if bound to THIS user, which we just ensured)
                if let Some(fcm) = fcm_token
                    && device_record.fcm_token != fcm
                {
                    let mut active_device: device::ActiveModel = device_record.into();
                    active_device.fcm_token = ActiveValue::Set(fcm);
                    active_device.updated_at = ActiveValue::Set(Utc::now());
                    active_device.update(txn).await?;
                }

                Ok(())
            })
        })
        .await?;
    }

    let token = auth::create_jwt(user.id, &state.config.jwt_secret)?;

    Ok(Json(AuthResponse {
        access_token: token,
        user: UserDto {
            id: user.id,
            email: user.email,
            enabled: user.enabled,
            admin: user.admin,
            token_version_at: user.token_version_at,
        },
    }))
}

pub async fn refresh_token(
    State(state): State<SharedState>,
    auth_user: auth::AuthUser,
) -> Result<impl IntoResponse, AppError> {
    let token = auth::create_jwt(auth_user.user.id, &state.config.jwt_secret)?;

    let mut response = Json(AuthResponse {
        access_token: token,
        user: UserDto {
            id: auth_user.user.id,
            email: auth_user.user.email,
            enabled: auth_user.user.enabled,
            admin: auth_user.user.admin,
            token_version_at: auth_user.user.token_version_at,
        },
    })
    .into_response();

    response.extensions_mut().insert(auth::SuppressTokenRenewal);

    Ok(response)
}
