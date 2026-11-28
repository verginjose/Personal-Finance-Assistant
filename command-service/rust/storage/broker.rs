//! RabbitMQ connection recovery and confirmed, durable OCR job publishing.

use super::Storage;
use crate::{Error, Result, env, required};
use axum::http::StatusCode;
use lapin::{
    BasicProperties, Channel, Connection, ConnectionProperties, ExchangeKind,
    options::{
        BasicPublishOptions, ConfirmSelectOptions, ExchangeDeclareOptions, QueueBindOptions,
        QueueDeclareOptions,
    },
    types::FieldTable,
};

pub fn confirmed_publish(confirmation: lapin::publisher_confirm::Confirmation) -> Result<()> {
    match confirmation {
        lapin::publisher_confirm::Confirmation::Ack(None) => Ok(()),
        lapin::publisher_confirm::Confirmation::Nack(_) => Err(Error(
            StatusCode::TOO_MANY_REQUESTS,
            "The receipt reader is busy. Please try again in 15 seconds.".into(),
        )),
        _ => Err(Error::internal("OCR job was not routed and confirmed")),
    }
}

impl Storage {
    pub(super) async fn connect_rabbit() -> Result<(Connection, Channel)> {
        let mut url = url::Url::parse(&format!(
            "amqp://{}:{}/%2f",
            env("RABBITMQ_HOST", "rabbitmq"),
            env("RABBITMQ_PORT", "5672")
        ))
        .map_err(Error::internal)?;
        url.set_username(&required("RABBITMQ_USER"))
            .map_err(|_| Error::internal("Invalid RabbitMQ user"))?;
        url.set_password(Some(&required("RABBITMQ_PASSWORD")))
            .map_err(|_| Error::internal("Invalid RabbitMQ password"))?;
        let connection = Connection::connect(url.as_str(), ConnectionProperties::default())
            .await
            .map_err(Error::internal)?;
        let channel = connection.create_channel().await.map_err(Error::internal)?;
        channel
            .exchange_declare(
                "finance-events",
                ExchangeKind::Topic,
                ExchangeDeclareOptions {
                    durable: true,
                    ..Default::default()
                },
                FieldTable::default(),
            )
            .await
            .map_err(Error::internal)?;
        channel
            .exchange_declare(
                "finance-events-dlx",
                ExchangeKind::Direct,
                ExchangeDeclareOptions {
                    durable: true,
                    ..Default::default()
                },
                FieldTable::default(),
            )
            .await
            .map_err(Error::internal)?;
        for (queue, exchange, key) in [
            ("ocr-jobs", "finance-events", "ocr.job"),
            ("ocr-jobs-dead", "finance-events-dlx", "ocr.job.dead"),
        ] {
            channel
                .queue_declare(
                    queue,
                    QueueDeclareOptions {
                        durable: true,
                        ..Default::default()
                    },
                    FieldTable::default(),
                )
                .await
                .map_err(Error::internal)?;
            channel
                .queue_bind(
                    queue,
                    exchange,
                    key,
                    QueueBindOptions::default(),
                    FieldTable::default(),
                )
                .await
                .map_err(Error::internal)?;
        }
        channel
            .confirm_select(ConfirmSelectOptions::default())
            .await
            .map_err(Error::internal)?;
        Ok((connection, channel))
    }
    pub(super) async fn rabbit_channel(&self) -> Result<Channel> {
        let mut rabbit = self.rabbit.lock().await;
        if !rabbit.0.status().connected() || !rabbit.1.status().connected() {
            *rabbit = Self::connect_rabbit().await?;
        }
        Ok(rabbit.1.clone())
    }
    pub(super) async fn publish(&self, event: serde_json::Value) -> Result<()> {
        let channel = self.rabbit_channel().await?;
        let bytes = serde_json::to_vec(&event).map_err(Error::internal)?;
        let confirmation = channel
            .basic_publish(
                "finance-events",
                "ocr.job",
                BasicPublishOptions {
                    mandatory: true,
                    ..Default::default()
                },
                &bytes,
                BasicProperties::default()
                    .with_content_type("application/json".into())
                    .with_delivery_mode(2),
            )
            .await
            .map_err(Error::internal)?
            .await
            .map_err(Error::internal)?;
        confirmed_publish(confirmation)
    }
}
