use sea_orm_migration::prelude::*;

#[derive(DeriveMigrationName)]
pub struct Migration;

#[async_trait::async_trait]
impl MigrationTrait for Migration {
    async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .alter_table(
                Table::alter()
                    .table(OtpRequest::Table)
                    .add_column(ColumnDef::new(OtpRequest::PubKey).string())
                    .to_owned(),
            )
            .await
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .alter_table(
                Table::alter()
                    .table(OtpRequest::Table)
                    .drop_column(OtpRequest::PubKey)
                    .to_owned(),
            )
            .await
    }
}

#[derive(DeriveIden)]
enum OtpRequest {
    Table,
    PubKey,
}
