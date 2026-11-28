//! Read-side ledger, analytics and planning service.
mod analytics;
mod app;
mod categories;
mod config;
mod data;
pub mod error;
mod groups;
mod health;
mod ledger;
pub mod observability;
mod planning;
mod replica;
pub mod request;
mod routes;
pub mod serialization;
mod startup;

pub use error::{Error, Result};
pub use startup::run;
