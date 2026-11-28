//! Shared MinIO/RabbitMQ clients, private-bucket initialization and readiness.

use super::object_store::digest;
use crate::{Error, Result, env, required};
use axum::http::StatusCode;
use lapin::{Channel, Connection};
use std::time::Duration;
use tokio::sync::Semaphore;

pub(crate) struct Storage {
    pub(super) client: reqwest::Client,
    pub(super) endpoint: String,
    pub(super) access: String,
    pub(super) secret: String,
    pub(super) receipts: String,
    pub(super) profiles: String,
    pub(super) rabbit: tokio::sync::Mutex<(Connection, Channel)>,
    pub(super) uploads: Semaphore,
}

impl Storage {
    pub async fn new() -> Result<Self> {
        let rabbit = Self::connect_rabbit().await?;
        let storage = Self {
            client: reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(5))
                .timeout(Duration::from_secs(120))
                .build()
                .map_err(Error::internal)?,
            endpoint: env("MINIO_ENDPOINT", "http://minio:9000"),
            access: required("MINIO_ACCESS_KEY"),
            secret: std::env::var("MINIO_SECRET_KEY")
                .unwrap_or_else(|_| required("MINIO_ROOT_PASSWORD")),
            receipts: env("MINIO_BUCKET", "receipts"),
            profiles: env("MINIO_PROFILE_BUCKET", "profiles"),
            rabbit: tokio::sync::Mutex::new(rabbit),
            uploads: Semaphore::new(2),
        };
        for bucket in [&storage.receipts, &storage.profiles] {
            let response = storage
                .request(reqwest::Method::HEAD, bucket, "", false, &digest(b""), None)
                .await?;
            if response.status() == StatusCode::NOT_FOUND {
                let response = storage
                    .request(reqwest::Method::PUT, bucket, "", false, &digest(b""), None)
                    .await?;
                if !response.status().is_success() && response.status() != StatusCode::CONFLICT {
                    return Err(Error::internal(format!(
                        "bucket creation returned {}",
                        response.status()
                    )));
                }
            } else if !response.status().is_success() {
                return Err(Error::internal(format!(
                    "bucket lookup returned {}",
                    response.status()
                )));
            }
            // Remove legacy public-read policies before opening the HTTP listener.
            let response = storage
                .request(
                    reqwest::Method::DELETE,
                    bucket,
                    "",
                    true,
                    &digest(b""),
                    None,
                )
                .await?;
            if !response.status().is_success() && response.status() != StatusCode::NOT_FOUND {
                return Err(Error::internal("Could not make bucket private"));
            }
        }
        Ok(storage)
    }
    pub async fn ready(&self) -> Result<()> {
        let response = self
            .client
            .get(format!("{}/minio/health/ready", self.endpoint))
            .send()
            .await
            .map_err(Error::internal)?;
        if !response.status().is_success() {
            return Err(Error::internal("MinIO unavailable"));
        }
        self.rabbit_channel().await?;
        Ok(())
    }
}
