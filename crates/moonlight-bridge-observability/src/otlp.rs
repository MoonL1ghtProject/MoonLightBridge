use opentelemetry_otlp::{Protocol, SpanExporter, WithExportConfig};
use opentelemetry_sdk::{Resource, trace::SdkTracerProvider};

/// Builds a caller-owned OTLP/HTTP tracer provider with a bounded background batch exporter.
///
/// The OpenTelemetry SDK chooses its default resource. Prefer
/// [`tracer_provider_with_service`] for a stable process identity in trace backends.
pub fn tracer_provider(
    endpoint: impl Into<String>,
) -> Result<SdkTracerProvider, opentelemetry_otlp::ExporterBuildError> {
    build_tracer_provider(endpoint, None)
}

/// Builds an OTLP/HTTP tracer provider with an explicit `service.name` resource attribute.
///
/// Use a different stable name for the Java client and Rust backend so a distributed trace is
/// grouped into separate service rows rather than `unknown_service`.
pub fn tracer_provider_with_service(
    endpoint: impl Into<String>,
    service_name: impl Into<String>,
) -> Result<SdkTracerProvider, opentelemetry_otlp::ExporterBuildError> {
    build_tracer_provider(endpoint, Some(service_name.into()))
}

fn build_tracer_provider(
    endpoint: impl Into<String>,
    service_name: Option<String>,
) -> Result<SdkTracerProvider, opentelemetry_otlp::ExporterBuildError> {
    let exporter = SpanExporter::builder()
        .with_http()
        .with_endpoint(endpoint)
        .with_protocol(Protocol::HttpBinary)
        .build()?;
    let mut builder = SdkTracerProvider::builder().with_batch_exporter(exporter);
    if let Some(service_name) = service_name {
        builder = builder.with_resource(service_resource(service_name));
    }
    Ok(builder.build())
}

fn service_resource(service_name: impl Into<String>) -> Resource {
    Resource::builder()
        .with_service_name(service_name.into())
        .build()
}

#[cfg(test)]
mod tests {
    use opentelemetry::Key;

    #[test]
    fn service_resource_sets_explicit_service_name() {
        let resource = super::service_resource("moonlight-observability-rust");
        assert_eq!(
            resource.get(&Key::new("service.name")).unwrap().as_str(),
            "moonlight-observability-rust"
        );
    }
}
