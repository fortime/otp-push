use axum::{Json, extract::State};
use common::UserDto;
use sea_orm::entity::prelude::*;

use crate::{auth::AuthUser, entities::user, error::AppError, state::SharedState};

pub async fn get_me(
    State(state): State<SharedState>,
    auth: AuthUser,
) -> Result<Json<UserDto>, AppError> {
    let u = user::Entity::find_by_id(auth.user.id)
        .one(&state.db)
        .await?
        .ok_or(AppError::AuthError {
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
