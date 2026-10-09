use moonlight_bridge_observability::prometheus::PrometheusSnapshotExporter;
use moonlight_bridge_server::{MoonLightMetrics, Router, Server, Telemetry, TelemetryChain};
use opentelemetry::trace::TracerProvider as _;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
};

#[path = "contract.rs"]
mod contract_source;

pub mod model {
    include!(concat!(
        env!("OUT_DIR"),
        "/moonlight.bridge.monitoring.v1.rs"
    ));
}
include!(concat!(env!("OUT_DIR"), "/moonlight_bridge_services.rs"));

use model::{PingRequest, PingResponse};

struct MonitoringServiceImpl;

impl MonitoringService for MonitoringServiceImpl {
    async fn ping(&self, request: PingRequest) -> Result<PingResponse, HandlerError> {
        Ok(PingResponse {
            message: format!("rust-backend:{}", request.message),
            handled_at_unix_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis()
                .min(i64::MAX as u128) as i64,
        })
    }
}

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let metrics = MoonLightMetrics::default();
    let mut telemetry: Vec<Arc<dyn Telemetry>> = vec![Arc::new(metrics.clone())];
    let otlp_provider = std::env::var("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        .ok()
        .map(|endpoint| {
            moonlight_bridge_observability::otlp::tracer_provider(endpoint)
                .map_err(std::io::Error::other)
        })
        .transpose()?;
    if let Some(provider) = &otlp_provider {
        telemetry.push(Arc::new(
            moonlight_bridge_observability::otel::OpenTelemetryTelemetry::new(
                provider.tracer("moonlight-observability-example"),
            ),
        ));
    }
    let router = register_monitoring_service(
        Router::builder().telemetry(Arc::new(TelemetryChain::new(telemetry))),
        Arc::new(MonitoringServiceImpl),
    )
    .build();
    let exporter = Arc::new(PrometheusSnapshotExporter::new().map_err(std::io::Error::other)?);
    let metrics_address =
        std::env::var("MOONLIGHT_METRICS_ADDRESS").unwrap_or_else(|_| "0.0.0.0:9898".to_owned());
    tokio::spawn(serve_metrics(metrics_address, metrics, exporter));

    let rpc_address =
        std::env::var("MOONLIGHT_BRIDGE_ADDRESS").unwrap_or_else(|_| "0.0.0.0:38192".to_owned());
    println!("MoonLightBridge RPC: tcp://{rpc_address}");
    println!("Rust Prometheus metrics: http://127.0.0.1:9898/metrics");
    let result = Server::bind_tcp(&rpc_address, router).await?.run().await;
    if let Some(provider) = otlp_provider {
        let _ = provider.shutdown();
    }
    result
}

async fn serve_metrics(
    address: String,
    metrics: MoonLightMetrics,
    exporter: Arc<PrometheusSnapshotExporter>,
) -> std::io::Result<()> {
    let listener = TcpListener::bind(address).await?;
    loop {
        let (mut stream, _) = listener.accept().await?;
        let exporter = exporter.clone();
        let metrics = metrics.clone();
        tokio::spawn(async move {
            let mut request = [0_u8; 1024];
            let _ = stream.read(&mut request).await;
            exporter.try_update(metrics.snapshot());
            let body = exporter.render().unwrap_or_default();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain; version=0.0.4\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = stream.write_all(response.as_bytes()).await;
        });
    }
}
