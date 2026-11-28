//! MinIO requests signed with AWS Signature Version 4.

use super::Storage;
use crate::{Error, Result, env};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

fn hmac(key: &[u8], data: &str) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).unwrap();
    mac.update(data.as_bytes());
    mac.finalize().into_bytes().to_vec()
}
pub(super) fn digest(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

impl Storage {
    pub(super) async fn request(
        &self,
        method: reqwest::Method,
        bucket: &str,
        object: &str,
        policy: bool,
        payload_hash: &str,
        payload: Option<(reqwest::Body, u64)>,
    ) -> Result<reqwest::Response> {
        let mut url = url::Url::parse(&self.endpoint).map_err(Error::internal)?;
        {
            let mut segments = url
                .path_segments_mut()
                .map_err(|_| Error::internal("Invalid MinIO endpoint"))?;
            segments.clear().push(bucket);
            if !object.is_empty() {
                for segment in object.split('/') {
                    segments.push(segment);
                }
            }
        }
        if policy {
            url.set_query(Some("policy="));
        }
        let timestamp = chrono::Utc::now();
        let date = timestamp.format("%Y%m%d").to_string();
        let amzdate = timestamp.format("%Y%m%dT%H%M%SZ").to_string();
        let host = match url.port() {
            Some(port) => format!("{}:{port}", url.host_str().unwrap()),
            None => url.host_str().unwrap().to_string(),
        };
        let signed = "host;x-amz-content-sha256;x-amz-date";
        let canonical = format!(
            "{}\n{}\n{}\nhost:{}\nx-amz-content-sha256:{}\nx-amz-date:{}\n\n{}\n{}",
            method,
            url.path(),
            url.query().unwrap_or(""),
            host,
            payload_hash,
            amzdate,
            signed,
            payload_hash
        );
        let region = env("MINIO_REGION", "us-east-1");
        let scope = format!("{date}/{region}/s3/aws4_request");
        let to_sign = format!(
            "AWS4-HMAC-SHA256\n{amzdate}\n{scope}\n{}",
            digest(canonical.as_bytes())
        );
        let date_key = hmac(format!("AWS4{}", self.secret).as_bytes(), &date);
        let region_key = hmac(&date_key, &region);
        let service_key = hmac(&region_key, "s3");
        let signing_key = hmac(&service_key, "aws4_request");
        let authorization = format!(
            "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed}, Signature={}",
            self.access,
            hex::encode(hmac(&signing_key, &to_sign))
        );
        let mut request = self
            .client
            .request(method, url)
            .header("host", host)
            .header("x-amz-date", amzdate)
            .header("x-amz-content-sha256", payload_hash)
            .header("authorization", authorization);
        if let Some((body, size)) = payload {
            request = request.header("content-length", size).body(body);
        }
        request.send().await.map_err(Error::internal)
    }
}
