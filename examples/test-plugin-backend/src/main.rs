use moonlight_bridge_example_api::{
    EchoService,
    model::{BackendNoticeEvent, EchoRequest, EchoResponse},
    publish_backend_notice_event, register_echo_service,
};
use moonlight_bridge_sentry::SentryMoonLightTelemetry;
use moonlight_bridge_server::{
    EventHub, HandlerError, MoonLightMetrics, Router, Server, ServerStream, Telemetry,
    TelemetryChain, iter_server_stream,
};
use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Default)]
struct PaperTestService {
    processed: AtomicU64,
    events: EventHub,
}

impl EchoService for PaperTestService {
    async fn echo(&self, request: EchoRequest) -> Result<EchoResponse, HandlerError> {
        let number = self.processed.fetch_add(1, Ordering::Relaxed) + 1;
        let message = format!("[Rust #{number}] {}", request.message.to_uppercase());
        publish_backend_notice_event(
            &self.events,
            BackendNoticeEvent {
                message: message.clone(),
            },
        );
        Ok(EchoResponse { message })
    }

    async fn stream_echo(
        &self,
        request: EchoRequest,
    ) -> Result<ServerStream<EchoResponse>, HandlerError> {
        Ok(iter_server_stream((0..3).map(move |index| {
            Ok(EchoResponse {
                message: format!("{}-{index}", request.message),
            })
        })))
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
    let router = register_echo_service(
        Router::builder().telemetry(Arc::new(telemetry)),
        Arc::new(PaperTestService {
            processed: AtomicU64::default(),
            events: events.clone(),
        }),
    )
    .build();

    #[cfg(unix)]
    if let Ok(path) = std::env::var("MOONLIGHT_BRIDGE_UNIX_PATH") {
        println!("MoonLightBridge Paper test backend listening on unix:{path}");
        return Server::bind_unix(path, router)?
            .with_event_hub(events)
            .run()
            .await;
    }

    let address =
        std::env::var("MOONLIGHT_BRIDGE_ADDRESS").unwrap_or_else(|_| "127.0.0.1:38201".to_owned());
    println!("MoonLightBridge Paper test backend listening on tcp://{address}");
    Server::bind_tcp(&address, router)
        .await?
        .with_event_hub(events)
        .run()
        .await
}
