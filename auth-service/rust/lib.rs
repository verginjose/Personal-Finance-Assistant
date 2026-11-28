//! Authentication, account profiles and session management.
mod app;
pub mod auth;
mod config;
pub mod error;
mod health;
pub mod observability;
mod routes;
mod startup;

pub(crate) use app::App;
pub use error::{Error, Result};
pub use startup::run;
