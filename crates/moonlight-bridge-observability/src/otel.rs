use moonlight_bridge_server::{RequestInfo, RequestObservation, RequestOutcome, Telemetry};
use opentelemetry::{
    Context, KeyValue,
    trace::{
        Span, SpanBuilder, SpanContext, SpanId, Status, TraceContextExt, TraceFlags, TraceId,
        TraceState, Tracer,
    },
};
use std::time::Duration;

/// OpenTelemetry request adapter using a caller-owned tracer/provider.
pub struct OpenTelemetryTelemetry<T> {
    tracer: T,
}

impl<T> OpenTelemetryTelemetry<T> {
    /// Uses the supplied tracer; exporter lifecycle remains owned by the application.
    pub fn new(tracer: T) -> Self {
        Self { tracer }
    }
}

impl<T> Telemetry for OpenTelemetryTelemetry<T>
where
    T: Tracer + Send + Sync + 'static,
    T::Span: Send,
{
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
        let builder = SpanBuilder::from_name(format!("moonlight.rpc.{:08x}", info.method_id))
            .with_attributes([
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
        Some(Box::new(OpenTelemetryObservation { span }))
    }
}

struct OpenTelemetryObservation<S> {
    span: S,
}

impl<S: Span + Send> RequestObservation for OpenTelemetryObservation<S> {
    fn record_stage(&mut self, name: &'static str, duration: Duration) {
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
        assert_eq!(spans.len(), 1);
        assert_eq!(spans[0].name, "moonlight.rpc.00001234");
        assert_eq!(spans[0].events.len(), 1);
        assert!(spans[0].attributes.iter().any(|attribute| {
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
