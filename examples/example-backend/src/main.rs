use moonlight_bridge_example_api::{
    EchoService,
    model::{BackendNoticeEvent, EchoRequest, EchoResponse},
    publish_backend_notice_event, register_echo_service,
};
use moonlight_bridge_sentry::SentryMoonLightTelemetry;
use moonlight_bridge_server::{
    EventHub, HandlerError, MoonLightMetrics, Router, Server, Telemetry, TelemetryChain,
    tls::load_mtls_server_config,
};
use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

const ECHO_METHOD_ID: u32 = 1;
const SLOW_METHOD_ID: u32 = 2;

struct ExampleEchoService(EventHub);

impl EchoService for ExampleEchoService {
    async fn echo(&self, request: EchoRequest) -> Result<EchoResponse, HandlerError> {
        publish_backend_notice_event(
            &self.0,
            BackendNoticeEvent {
                message: format!("processed:{}", request.message),
            },
        );
        Ok(EchoResponse {
            message: request.message,
        })
    }
}

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let _sentry_guard = moonlight_bridge_sentry::init_framework_sentry();
    tracing_subscriber::registry()
        .with(
            moonlight_bridge_sentry::sentry_tracing::layer()
                .event_filter(moonlight_bridge_sentry::framework_event_filter),
        )
        .init();
    let metrics = MoonLightMetrics::default();
    let telemetry = TelemetryChain::new([
        Arc::new(metrics) as Arc<dyn Telemetry>,
        Arc::new(SentryMoonLightTelemetry) as Arc<dyn Telemetry>,
    ]);
    let events = EventHub::default();
    let builder = Router::builder()
        .telemetry(Arc::new(telemetry))
        .route(ECHO_METHOD_ID, |body| async move { Ok(body) })
        .route(SLOW_METHOD_ID, |body| async move {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            Ok(body)
        });
    let router =
        register_echo_service(builder, Arc::new(ExampleEchoService(events.clone()))).build();

    #[cfg(unix)]
    if let Ok(path) = std::env::var("MOONLIGHT_BRIDGE_UNIX_PATH") {
        println!("MoonLightBridge example backend listening on unix:{path}");
        return Server::bind_unix(path, router)?
            .with_event_hub(events)
            .run()
            .await;
    }

    let address =
        std::env::var("MOONLIGHT_BRIDGE_ADDRESS").unwrap_or_else(|_| "127.0.0.1:38191".to_owned());
    if let (Ok(certificate), Ok(key), Ok(client_ca)) = (
        std::env::var("MOONLIGHT_BRIDGE_TLS_CERT"),
        std::env::var("MOONLIGHT_BRIDGE_TLS_KEY"),
        std::env::var("MOONLIGHT_BRIDGE_TLS_CLIENT_CA"),
    ) {
        println!("MoonLightBridge example backend listening with mTLS on tls:{address}");
        let config = load_mtls_server_config(certificate, key, client_ca)?;
        return Server::bind_tls(&address, router, config)
            .await?
            .with_event_hub(events)
            .run()
            .await;
    }
    println!("MoonLightBridge example backend listening on tcp:{address}");
    Server::bind_tcp(&address, router)
        .await?
        .with_event_hub(events)
        .run()
        .await
}
