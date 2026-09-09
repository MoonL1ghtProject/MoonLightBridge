use expj_example_api::{
    EchoService,
    model::{EchoRequest, EchoResponse},
    register_echo_service,
};
use expj_sentry::SentryExpjTelemetry;
use expj_server::{
    ExpjMetrics, HandlerError, Router, Server, Telemetry, TelemetryChain,
    tls::load_mtls_server_config,
};
use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

const ECHO_METHOD_ID: u32 = 1;
const SLOW_METHOD_ID: u32 = 2;

struct ExampleEchoService;

impl EchoService for ExampleEchoService {
    async fn echo(&self, request: EchoRequest) -> Result<EchoResponse, HandlerError> {
        Ok(EchoResponse {
            message: request.message,
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
                } else if *metadata.level() == tracing::Level::WARN {
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
    let builder = Router::builder()
        .telemetry(Arc::new(telemetry))
        .route(ECHO_METHOD_ID, |body| async move { Ok(body) })
        .route(SLOW_METHOD_ID, |body| async move {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            Ok(body)
        });
    let router = register_echo_service(builder, Arc::new(ExampleEchoService)).build();

    #[cfg(unix)]
    if let Ok(path) = std::env::var("EXPJ_UNIX_PATH") {
        println!("EXPJ example backend listening on unix:{path}");
        return Server::bind_unix(path, router)?.run().await;
    }

    let address = std::env::var("EXPJ_ADDRESS").unwrap_or_else(|_| "127.0.0.1:38191".to_owned());
    if let (Ok(certificate), Ok(key), Ok(client_ca)) = (
        std::env::var("EXPJ_TLS_CERT"),
        std::env::var("EXPJ_TLS_KEY"),
        std::env::var("EXPJ_TLS_CLIENT_CA"),
    ) {
        println!("EXPJ example backend listening with mTLS on tls:{address}");
        let config = load_mtls_server_config(certificate, key, client_ca)?;
        return Server::bind_tls(&address, router, config)
            .await?
            .run()
            .await;
    }
    println!("EXPJ example backend listening on tcp:{address}");
    Server::bind_tcp(&address, router).await?.run().await
}
