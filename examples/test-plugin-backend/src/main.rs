use expj_example_api::{
    EchoService,
    model::{EchoRequest, EchoResponse},
    register_echo_service,
};
use expj_sentry::SentryExpjTelemetry;
use expj_server::{ExpjMetrics, HandlerError, Router, Server, Telemetry, TelemetryChain};
use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Default)]
struct PaperTestService {
    processed: AtomicU64,
}

impl EchoService for PaperTestService {
    async fn echo(&self, request: EchoRequest) -> Result<EchoResponse, HandlerError> {
        let number = self.processed.fetch_add(1, Ordering::Relaxed) + 1;
        Ok(EchoResponse {
            message: format!("[Rust #{number}] {}", request.message.to_uppercase()),
        })
    }
}

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let _sentry_guard = expj_sentry::init_framework_sentry();
    tracing_subscriber::registry()
        .with(
            expj_sentry::sentry_tracing::layer().event_filter(|metadata| {
                use expj_sentry::sentry_tracing::EventFilter;
                if !metadata.target().starts_with("expj") {
                    EventFilter::Ignore
                } else if *metadata.level() == tracing::Level::ERROR {
                    EventFilter::Event | EventFilter::Log
                } else if matches!(
                    *metadata.level(),
                    tracing::Level::WARN | tracing::Level::TRACE
                ) {
                    EventFilter::Log
                } else {
                    EventFilter::Ignore
                }
            }),
        )
        .init();

    let metrics = ExpjMetrics::default();
    let telemetry = TelemetryChain::new([
        Arc::new(metrics) as Arc<dyn Telemetry>,
        Arc::new(SentryExpjTelemetry) as Arc<dyn Telemetry>,
    ]);
    let router = register_echo_service(
        Router::builder().telemetry(Arc::new(telemetry)),
        Arc::new(PaperTestService::default()),
    )
    .build();

    #[cfg(unix)]
    if let Ok(path) = std::env::var("EXPJ_UNIX_PATH") {
        println!("EXPJ Paper test backend listening on unix:{path}");
        return Server::bind_unix(path, router)?.run().await;
    }

    let address = std::env::var("EXPJ_ADDRESS").unwrap_or_else(|_| "127.0.0.1:38201".to_owned());
    println!("EXPJ Paper test backend listening on tcp://{address}");
    Server::bind_tcp(&address, router).await?.run().await
}
