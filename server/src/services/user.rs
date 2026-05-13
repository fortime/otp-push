use sea_orm::{ColumnTrait, DatabaseConnection, EntityTrait, QueryFilter};
use uuid::Uuid;

use crate::{entities::user_limit, error::AppError};

pub struct DefaultUserLimit;

pub trait UserLimitTrait {
    fn max_otp_records(&self) -> i32;

    fn max_tokens_per_record(&self) -> i32;
}

impl UserLimitTrait for DefaultUserLimit {
    fn max_otp_records(&self) -> i32 {
        1
    }

    fn max_tokens_per_record(&self) -> i32 {
        3
    }
}

impl UserLimitTrait for user_limit::Model {
    fn max_otp_records(&self) -> i32 {
        self.max_otp_records
    }

    fn max_tokens_per_record(&self) -> i32 {
        self.max_tokens_per_record
    }
}

pub async fn get_user_limits(
    db: &DatabaseConnection,
    user_id: Uuid,
) -> Result<Option<user_limit::Model>, AppError> {
    Ok(user_limit::Entity::find()
        .filter(user_limit::Column::UserId.eq(user_id))
        .one(db)
        .await?)
}
