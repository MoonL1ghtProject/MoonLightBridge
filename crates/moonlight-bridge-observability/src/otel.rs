use moonlight_bridge_server::{RequestInfo, RequestObservation, RequestOutcome, Telemetry};
use opentelemetry::{
    Context, KeyValue,
    trace::{
        Span, SpanBuilder, SpanContext, SpanId, Status, TraceContextExt, TraceFlags, TraceId,
        TraceState, Tracer,
    },
};
use std::time::{Duration, SystemTime};

/// OpenTelemetry request adapter using a caller-owned tracer/provider.
///
/// Generated routes become `Service/Method` request spans. When the client propagates a trace
/// context, this adapter creates the Rust request span as a child of the Java client span and
/// exports recorded backend stages as children of the Rust span. Raw routes fall back to a stable
/// `moonlight.rpc.<method-id>` name.
pub struct OpenTelemetryTelemetry<T> {
    tracer: T,
}

impl<T> OpenTelemetryTelemetry<T> {
    /// Uses the supplied tracer; exporter lifecycle and `service.name` remain application-owned.
    pub fn new(tracer: T) -> Self {
        Self { tracer }
    }
}

impl<T> Telemetry for OpenTelemetryTelemetry<T>
where
    T: Tracer + Clone + Send + Sync + 'static,
    T::Span: Send,
{
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
        let span_name = info
            .method_name
            .map(str::to_owned)
            .unwrap_or_else(|| format!("moonlight.rpc.{:08x}", info.method_id));
        let builder = SpanBuilder::from_name(span_name).with_attributes([
            KeyValue::new("rpc.system", "moonlight_bridge"),
            KeyValue::new("rpc.method_id", i64::from(info.method_id)),
            KeyValue::new("rpc.request_id", info.request_id.to_string()),
            KeyValue::new("rpc.request.size", info.request_bytes as i64),
        ]);
        let span = if let Some(parent) = info.trace_context {
            let flags = if parent.sampled {
                TraceFlags::SAMPLED
            } else {
                TraceFlags::default()
            };
            let context = Context::new().with_remote_span_context(SpanContext::new(
                TraceId::from_bytes(parent.trace_id),
                SpanId::from_bytes(parent.parent_span_id),
                flags,
                true,
                TraceState::default(),
            ));
            builder.start_with_context(&self.tracer, &context)
        } else {
            builder.start(&self.tracer)
        };
        Some(Box::new(OpenTelemetryObservation {
            tracer: self.tracer.clone(),
            span,
        }))
    }
}

struct OpenTelemetryObservation<T, S> {
    tracer: T,
    span: S,
}

impl<T, S> RequestObservation for OpenTelemetryObservation<T, S>
where
    T: Tracer + Send,
    T::Span: Send,
    S: Span + Send,
{
    fn record_stage(&mut self, name: &'static str, duration: Duration) {
        let finished_at = SystemTime::now();
        let started_at = finished_at.checked_sub(duration).unwrap_or(finished_at);
        let parent = Context::new().with_remote_span_context(self.span.span_context().clone());
        let mut stage_span = SpanBuilder::from_name(name)
            .with_start_time(started_at)
            .with_attributes([KeyValue::new(
                "stage.duration_ns",
                duration.as_nanos().min(i64::MAX as u128) as i64,
            )])
            .start_with_context(&self.tracer, &parent);
        stage_span.end_with_timestamp(finished_at);
        self.span.add_event(
            "moonlight.stage",
            vec![
                KeyValue::new("stage.name", name),
                KeyValue::new(
                    "stage.duration_ns",
                    duration.as_nanos().min(i64::MAX as u128) as i64,
                ),
            ],
        );
    }

    fn finish(mut self: Box<Self>, outcome: RequestOutcome) {
        match outcome {
            RequestOutcome::Success { response_bytes } => {
                self.span
                    .set_attribute(KeyValue::new("rpc.response.size", response_bytes as i64));
                self.span.set_status(Status::Ok);
            }
            RequestOutcome::Error { code } => {
                self.span
                    .set_attribute(KeyValue::new("error.type", format!("{code:?}")));
                self.span.set_status(Status::error(format!("{code:?}")));
            }
        }
        self.span.end();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use moonlight_bridge_protocol::ErrorCode;
    use opentelemetry::trace::TracerProvider as _;
    use opentelemetry_sdk::trace::{InMemorySpanExporter, SdkTracerProvider};

    #[test]
    fn exports_request_span_and_stage_with_full_unsigned_request_id() {
        let exporter = InMemorySpanExporter::default();
        let provider = SdkTracerProvider::builder()
            .with_simple_exporter(exporter.clone())
            .build();
        let telemetry = OpenTelemetryTelemetry::new(provider.tracer("moonlight-test"));
        let mut observation = telemetry
            .start_request(RequestInfo {
                method_id: 0x1234,
                method_name: Some("MonitoringService/Ping"),
                request_id: u64::MAX,
                request_bytes: 42,
                trace_context: None,
            })
            .unwrap();
        observation.record_stage("decode", Duration::from_micros(4));
        observation.finish(RequestOutcome::Error {
            code: ErrorCode::Unavailable,
        });
        provider.force_flush().unwrap();

        let spans = exporter.get_finished_spans().unwrap();
        assert_eq!(spans.len(), 2);
        let request_span = spans
            .iter()
            .find(|span| span.name == "MonitoringService/Ping")
            .unwrap();
        let stage_span = spans.iter().find(|span| span.name == "decode").unwrap();
        assert_eq!(
            stage_span.parent_span_id,
            request_span.span_context.span_id()
        );
        assert_eq!(request_span.events.len(), 1);
        assert!(request_span.attributes.iter().any(|attribute| {
            attribute.key.as_str() == "rpc.request_id"
                && attribute.value.as_str() == "18446744073709551615"
        }));
    }

    #[test]
    fn server_span_continues_the_propagated_java_trace() {
        let exporter = InMemorySpanExporter::default();
        let provider = SdkTracerProvider::builder()
            .with_simple_exporter(exporter.clone())
            .build();
        let telemetry = OpenTelemetryTelemetry::new(provider.tracer("moonlight-test"));
        telemetry
            .start_request(RequestInfo {
                method_id: 1,
                method_name: Some("MonitoringService/Ping"),
                request_id: 2,
                request_bytes: 3,
                trace_context: Some(moonlight_bridge_protocol::TraceContext {
                    trace_id: [7; 16],
                    parent_span_id: [9; 8],
                    sampled: true,
                }),
            })
            .unwrap()
            .finish(RequestOutcome::Success { response_bytes: 4 });
        provider.force_flush().unwrap();

        let spans = exporter.get_finished_spans().unwrap();
        assert_eq!(spans[0].span_context.trace_id().to_bytes(), [7; 16]);
        assert_eq!(spans[0].parent_span_id.to_bytes(), [9; 8]);
    }
}
