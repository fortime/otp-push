use axum::{Json, extract::State};
use common::UserDto;
use sea_orm::entity::prelude::*;

use crate::http::{auth::AuthUser, entities::user, error::AppHttpError, state::SharedAppHttpState};

pub async fn get_me(
    State(state): State<SharedAppHttpState>,
    auth: AuthUser,
) -> Result<Json<UserDto>, AppHttpError> {
    let u = user::Entity::find_by_id(auth.user.id)
        .one(&state.db)
        .await?
        .ok_or(AppHttpError::AuthError {
            message: "User not found".to_string(),
        })?;

    Ok(Json(UserDto {
        id: u.id,
        email: u.email,
        enabled: u.enabled,
        admin: u.admin,
        token_version_at: u.token_version_at,
    }))
}
