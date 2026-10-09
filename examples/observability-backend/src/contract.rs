#[moonlight::contract(
    package = "moonlight.bridge.monitoring.v1",
    java_package = "ru.moonlightproject.bridge.monitoring.v1"
)]
mod contract {
    #[moonlight::message]
    pub struct PingRequest {
        pub message: String,
    }

    #[moonlight::message]
    pub struct PingResponse {
        pub message: String,
        pub handled_at_unix_ms: i64,
    }

    #[moonlight::service]
    pub trait MonitoringService {
        async fn ping(request: PingRequest) -> PingResponse;
    }
}
