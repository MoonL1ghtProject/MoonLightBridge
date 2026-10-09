# MoonLightBridge observability example

This example shares one standard Protobuf contract from
`proto/monitoring.proto` between a Rust service and a Java client, exports aggregate
metrics from both runtimes to Prometheus, and propagates one OpenTelemetry trace across the Java to
Rust RPC boundary. Grafana starts with Prometheus and Tempo data sources plus a provisioned
MoonLightBridge dashboard.

From the repository root, start monitoring:

```bash
docker compose -f examples/observability/docker-compose.yml up -d
```

Start the Rust backend with Prometheus metrics and OTLP traces:

```bash
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces \
  cargo run -p moonlight-bridge-observability-backend
```

In another terminal, start the Java traffic generator:

```bash
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces \
  ./gradlew :examples:observability-client:run
```

Open Grafana at <http://localhost:3000> and sign in with `admin` / `admin`. The
**MoonLightBridge / MoonLightBridge Java ↔ Rust** dashboard shows both metric producers. Open
**Explore**, select **Tempo**, and search recent traces to inspect the Java client span and its Rust
server child span. The shared trace includes `rpc.request_id`, method ID, sizes, result status, and
Rust handler stages. Prometheus is available directly at <http://localhost:9090>.

The applications own their metrics endpoints and OTLP lifecycle. Adding an observability module by
itself never opens a port.
