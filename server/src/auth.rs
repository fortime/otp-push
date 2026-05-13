use axum::{
    async_trait,
    body::Body,
    extract::{FromRef, FromRequestParts, State},
    http::{Request, request::Parts},
    middleware::Next,
    response::Response,
};
use chrono::{Duration, Utc};
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation};
use sea_orm::{ColumnTrait, EntityTrait, QueryFilter};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    entities::{api_access_token, user},
    error::AppError,
    state::SharedState,
};

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: Uuid,
    pub iat: usize, // Issued At
    pub exp: usize,
}

#[derive(Clone, Copy, Debug)]
pub struct RenewTokenSignal;

#[derive(Clone, Copy, Debug)]
pub struct SuppressTokenRenewal;

pub fn create_jwt(user_id: Uuid, secret: &str) -> Result<String, AppError> {
    let now = Utc::now();
    let expiration = now
        .checked_add_signed(Duration::days(7))
        .ok_or(AppError::Internal {
            message: "Failed to calculate token expiration".to_string(),
        })?
        .timestamp();

    let claims = Claims {
        sub: user_id,
        iat: now.timestamp() as usize,
        exp: expiration as usize,
    };

    jsonwebtoken::encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_ref()),
    )
    .map_err(|e| AppError::Internal {
        message: format!("Failed to encode JWT: {}", e),
    })
}

#[derive(Clone)]
pub struct AuthUser {
    pub user: user::Model,
}

#[async_trait]
impl<S> FromRequestParts<S> for AuthUser
where
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        parts
            .extensions
            .get::<AuthUser>()
            .cloned()
            .ok_or(AppError::AuthError {
                message: "Authentication required".to_string(),
            })
    }
}

pub async fn auth_middleware(
    State(state): State<SharedState>,
    mut req: Request<Body>,
    next: Next,
) -> Result<Response, AppError> {
    let auth_header = req
        .headers()
        .get("Authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or_else(|| AppError::AuthError {
            message: "Missing authorization header".to_string(),
        })?;

    let token_data = jsonwebtoken::decode::<Claims>(
        auth_header,
        &DecodingKey::from_secret(state.config.jwt_secret.as_ref()),
        &Validation::default(),
    )
    .map_err(|_| AppError::AuthError {
        message: "Invalid token".to_string(),
    })?;

    let user = user::Entity::find_by_id(token_data.claims.sub)
        .one(&state.db)
        .await
        .map_err(|e| AppError::DatabaseError { source: e })?
        .ok_or(AppError::AuthError {
            message: "User not found".to_string(),
        })?;

    if !user.enabled {
        return Err(AppError::UserDisabled);
    }

    let token_version_seconds = user.token_version_at.timestamp() as usize;
    if token_data.claims.iat < token_version_seconds {
        return Err(AppError::AuthError {
            message: "Token revoked".to_string(),
        });
    }

    let mut renew_signal = false;
    // Check for renewal (3 days)
    let three_days_in_seconds = 3 * 24 * 60 * 60;
    let now = Utc::now().timestamp() as usize;
    if token_data.claims.exp - now < three_days_in_seconds {
        renew_signal = true;
        req.extensions_mut().insert(RenewTokenSignal);
    }

    req.extensions_mut().insert(AuthUser { user });

    let mut response = next.run(req).await;

    if renew_signal
        && response
            .extensions()
            .get::<SuppressTokenRenewal>()
            .is_none()
    {
        if let Ok(value) = "true".parse() {
            response.headers_mut().insert("X-Renew-Token", value);
        }
    }

    Ok(response)
}

pub struct AdminAuthUser(pub AuthUser);

#[async_trait]
impl<S> FromRequestParts<S> for AdminAuthUser
where
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let auth_user = AuthUser::from_request_parts(parts, state).await?;
        if !auth_user.user.admin {
            return Err(AppError::AuthError {
                message: "Forbidden".to_string(),
            });
        }
        Ok(AdminAuthUser(auth_user))
    }
}

pub struct ApiTokenAuth {
    pub token: api_access_token::Model,
}

#[async_trait]
impl<S> FromRequestParts<S> for ApiTokenAuth
where
    SharedState: FromRef<S>,
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let state = SharedState::from_ref(state);

        let token = parts
            .headers
            .get("X-Api-Token")
            .and_then(|v| v.to_str().ok())
            .ok_or(AppError::AuthError {
                message: "Missing API token".to_string(),
            })?;

        let token_model = api_access_token::Entity::find()
            .filter(api_access_token::Column::TokenHash.eq(token))
            .one(&state.db)
            .await
            .map_err(|e| AppError::DatabaseError { source: e })?
            .ok_or(AppError::AuthError {
                message: "Invalid API token".to_string(),
            })?;

        Ok(ApiTokenAuth { token: token_model })
    }
}
