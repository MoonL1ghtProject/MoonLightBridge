//! Optional Sentry adapter for MoonLightBridge. Payload contents are never recorded.

use moonlight_bridge_server::{RequestInfo, RequestObservation, RequestOutcome, Telemetry};
use sentry::{Transaction, TransactionContext, protocol::SpanStatus};
use std::time::Instant;

pub use sentry_tracing;

const FRAMEWORK_DSN: &str = "https://62dd4dd1aa9a802c30444e9423cb0faa@o4511248228941824.ingest.us.sentry.io/4511981091487744";

/// Initializes the MoonLightBridge-owned Sentry client. Keep the returned guard alive for the process lifetime.
pub fn init_framework_sentry() -> sentry::ClientInitGuard {
    let environment =
        option_env!("MOONLIGHT_BRIDGE_INTERNAL_TELEMETRY_ENVIRONMENT").unwrap_or("production");
    let release = option_env!("MOONLIGHT_BRIDGE_INTERNAL_TELEMETRY_RELEASE")
        .unwrap_or("moonlight-bridge@0.1.0");
    let options = sentry::ClientOptions::new()
        .release(release)
        .environment(environment)
        .traces_sample_rate(1.0)
        .send_default_pii(false)
        .default_integrations(false);
    sentry::init((FRAMEWORK_DSN, options))
}

/// Consistent filtering for non-request framework events. Request completion
/// logs and request errors are emitted directly by [`SentryMoonLightTelemetry`] so
/// they do not depend on an application's subscriber or trace sampling rate.
pub fn framework_event_filter(metadata: &tracing::Metadata<'_>) -> sentry_tracing::EventFilter {
    use sentry_tracing::EventFilter;
    if !metadata.target().starts_with("moonlight_bridge") {
        EventFilter::Ignore
    } else if *metadata.level() == tracing::Level::ERROR
        || *metadata.level() == tracing::Level::WARN
    {
        EventFilter::Log
    } else {
        EventFilter::Ignore
    }
}

#[derive(Debug, Default)]
pub struct SentryMoonLightTelemetry;

impl Telemetry for SentryMoonLightTelemetry {
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
        let transaction = info.trace_context.map(|trace| {
            let trace_header = format!(
                "{}-{}-{}",
                hex(&trace.trace_id),
                hex(&trace.parent_span_id),
                if trace.sampled { '1' } else { '0' }
            );
            let name = format!("MoonLightBridge method {}", info.method_id);
            let context = TransactionContext::continue_from_headers(
                &name,
                "rpc.server",
                [("sentry-trace", trace_header.as_str())],
            );
            let transaction = sentry::start_transaction(context);
            transaction.set_data("rpc.system", "moonlight-bridge".into());
            transaction.set_data("rpc.method_id", info.method_id.into());
            transaction.set_data("moonlight_bridge.request_id", info.request_id.into());
            transaction.set_data(
                "moonlight_bridge.request_bytes",
                (info.request_bytes as u64).into(),
            );
            transaction
        });
        Some(Box::new(SentryObservation {
            transaction,
            info,
            started: Instant::now(),
            finished: false,
        }))
    }
}

struct SentryObservation {
    transaction: Option<Transaction>,
    info: RequestInfo,
    started: Instant,
    finished: bool,
}

impl RequestObservation for SentryObservation {
    fn finish(mut self: Box<Self>, outcome: RequestOutcome) {
        self.finished = true;
        self.record(outcome);
    }
}

impl SentryObservation {
    fn record(&mut self, outcome: RequestOutcome) {
        let transaction = self.transaction.take();
        let duration_micros = self.started.elapsed().as_micros() as u64;
        match outcome {
            RequestOutcome::Success { response_bytes } => {
                if let Some(transaction) = transaction {
                    transaction.set_data(
                        "moonlight_bridge.response_bytes",
                        (response_bytes as u64).into(),
                    );
                    transaction.set_status(SpanStatus::Ok);
                    transaction.finish();
                }
                if success_logs_enabled() {
                    sentry::logger_debug!(
                        rpc.system = "moonlight-bridge",
                        rpc.method_id = self.info.method_id as u64,
                        moonlight_bridge.request_id = self.info.request_id,
                        moonlight_bridge.request_bytes = self.info.request_bytes as u64,
                        moonlight_bridge.response_bytes = response_bytes as u64,
                        moonlight_bridge.duration_us = duration_micros,
                        "MoonLightBridge request completed"
                    );
                }
            }
            RequestOutcome::Error { code } => {
                if let Some(transaction) = transaction {
                    transaction.set_data("moonlight_bridge.error_code", format!("{code:?}").into());
                    transaction.set_status(match code {
                        moonlight_bridge_server::ErrorCode::DeadlineExceeded => {
                            SpanStatus::DeadlineExceeded
                        }
                        moonlight_bridge_server::ErrorCode::Cancelled => SpanStatus::Cancelled,
                        moonlight_bridge_server::ErrorCode::UnknownMethod => {
                            SpanStatus::Unimplemented
                        }
                        moonlight_bridge_server::ErrorCode::InvalidRequest => {
                            SpanStatus::InvalidArgument
                        }
                        moonlight_bridge_server::ErrorCode::ResourceExhausted => {
                            SpanStatus::ResourceExhausted
                        }
                        moonlight_bridge_server::ErrorCode::Internal => SpanStatus::InternalError,
                    });
                    transaction.finish();
                }
                sentry::with_scope(
                    |scope| {
                        scope.set_tag("rpc.system", "moonlight-bridge");
                        scope.set_tag("rpc.method_id", self.info.method_id.to_string());
                        scope.set_extra("moonlight_bridge.request_id", self.info.request_id.into());
                        scope.set_extra(
                            "moonlight_bridge.request_bytes",
                            (self.info.request_bytes as u64).into(),
                        );
                        scope.set_extra("moonlight_bridge.duration_us", duration_micros.into());
                        scope.set_extra("moonlight_bridge.error_code", format!("{code:?}").into());
                    },
                    || {
                        sentry::capture_message(
                            "MoonLightBridge RPC request failed",
                            sentry::Level::Error,
                        );
                    },
                );
            }
        }
    }
}

fn success_logs_enabled() -> bool {
    option_env!("MOONLIGHT_BRIDGE_INTERNAL_TELEMETRY_SUCCESS_LOGS") == Some("true")
}

impl Drop for SentryObservation {
    fn drop(&mut self) {
        if !self.finished {
            self.record(RequestOutcome::Error {
                code: moonlight_bridge_server::ErrorCode::Cancelled,
            });
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        output.push(DIGITS[(byte >> 4) as usize] as char);
        output.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trace_ids_are_lower_hex() {
        assert_eq!(hex(&[0x00, 0xab, 0xff]), "00abff");
    }

    #[test]
    fn requests_without_trace_context_still_create_log_observations() {
        let observation = SentryMoonLightTelemetry.start_request(RequestInfo {
            method_id: 7,
            request_id: 11,
            request_bytes: 13,
            trace_context: None,
        });
        assert!(observation.is_some());
        observation
            .unwrap()
            .finish(RequestOutcome::Success { response_bytes: 17 });
    }
}
