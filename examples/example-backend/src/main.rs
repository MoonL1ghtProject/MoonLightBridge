use moonlight_bridge_example_api::{
    EchoService,
    model::{BackendNoticeEvent, EchoRequest, EchoResponse},
    publish_backend_notice_event, register_echo_service,
};
use moonlight_bridge_sentry::SentryMoonLightTelemetry;
use moonlight_bridge_server::{
    EventHub, HandlerError, MoonLightMetrics, Router, Server, ServerStream, Telemetry,
    TelemetryChain, iter_server_stream, tls::load_mtls_server_config,
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
    let tls = tls_paths([
        std::env::var_os("MOONLIGHT_BRIDGE_TLS_CERT"),
        std::env::var_os("MOONLIGHT_BRIDGE_TLS_KEY"),
        std::env::var_os("MOONLIGHT_BRIDGE_TLS_CLIENT_CA"),
    ])?;
    if let Some([certificate, key, client_ca]) = tls {
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

fn tls_paths(
    paths: [Option<std::ffi::OsString>; 3],
) -> std::io::Result<Option<[std::ffi::OsString; 3]>> {
    match paths {
        [None, None, None] => Ok(None),
        [Some(certificate), Some(key), Some(ca)]
            if !certificate.is_empty() && !key.is_empty() && !ca.is_empty() =>
        {
            Ok(Some([certificate, key, ca]))
        }
        _ => Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "TLS requires nonempty MOONLIGHT_BRIDGE_TLS_CERT, MOONLIGHT_BRIDGE_TLS_KEY and MOONLIGHT_BRIDGE_TLS_CLIENT_CA together",
        )),
    }
}

#[cfg(test)]
mod configuration_tests {
    use super::tls_paths;

    #[test]
    fn partial_or_empty_tls_configuration_cannot_fall_back_to_plaintext() {
        for mask in 1..7 {
            let paths = std::array::from_fn(|index| {
                (mask & (1 << index) != 0).then(|| "configured.pem".into())
            });
            assert!(tls_paths(paths).is_err());
        }
        assert!(tls_paths([Some("".into()), Some("key".into()), Some("ca".into())]).is_err());
        assert!(tls_paths([None, None, None]).unwrap().is_none());
        assert!(
            tls_paths([Some("cert".into()), Some("key".into()), Some("ca".into())])
                .unwrap()
                .is_some()
        );
    }
}
