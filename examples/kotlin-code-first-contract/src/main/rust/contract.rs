#[moonlight::contract(
    package = "moonlight.kotlin.v1",
    java_package = "example.kotlin.generated"
)]
mod contract {
    #[moonlight::message]
    struct PingRequest {
        message: String,
        attempts: Option<i32>,
        tags: Vec<String>,
    }

    #[moonlight::message]
    struct PingResponse {
        message: String,
    }

    #[moonlight::enumeration]
    enum DeliveryState {
        Unknown,
        Ready,
    }

    #[moonlight::service]
    trait MonitoringService {
        #[moonlight::rpc(
            timeout_ms = 1500,
            idle_timeout_ms = 0,
            max_attempts = 3,
            initial_backoff_ms = 25,
            max_backoff_ms = 1000,
            multiplier_milli = 2000,
            idempotency = "idempotent",
            max_request_bytes = 16777216,
            max_response_bytes = 16777216,
            required_scope = "monitoring.ping",
            compression = "prefer",
            trace_sample_per_million = 0
        )]
        async fn ping(request: PingRequest) -> PingResponse;
    }
}
