# MoonLightBridge observability example

The Rust service declares its contract in
[`examples/observability-backend/src/contract.rs`](../observability-backend/src/contract.rs).
The build generates a standard Protobuf schema, Rust bindings, Java messages, and the typed Java
client from that source. It exports aggregate metrics from both runtimes to Prometheus and
propagates one OpenTelemetry trace across the Java-to-Rust RPC boundary. Grafana starts with
Prometheus and Tempo data sources plus a provisioned MoonLightBridge dashboard.

From the repository root, start monitoring:

```bash
docker compose -f examples/observability/docker-compose.yml up -d
```

On Linux with UFW enabled, allow Prometheus to scrape the two host-side metrics endpoints. Docker's
`host.docker.internal` address is not necessarily the gateway of the Compose network, so derive all
three values from the running stack:

```bash
OBS_NETWORK=observability_default
OBS_NETWORK_ID=$(sudo docker network inspect -f '{{.Id}}' "$OBS_NETWORK")
OBS_BRIDGE="br-${OBS_NETWORK_ID:0:12}"
OBS_SUBNET=$(sudo docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' "$OBS_NETWORK")
OBS_HOST_GATEWAY=$(sudo docker compose \
  -f examples/observability/docker-compose.yml \
  exec -T prometheus awk '$2 == "host.docker.internal" { print $1 }' /etc/hosts </dev/null)

sudo ufw allow in on "$OBS_BRIDGE" from "$OBS_SUBNET" \
  to "$OBS_HOST_GATEWAY" port 9898 proto tcp
sudo ufw allow in on "$OBS_BRIDGE" from "$OBS_SUBNET" \
  to "$OBS_HOST_GATEWAY" port 9899 proto tcp
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

Confirm that both scrape targets return `1` before opening Grafana:

```bash
curl --noproxy '*' -s \
  'http://127.0.0.1:9090/api/v1/query?query=up'
```

Open Grafana at <http://localhost:3000> and sign in with `admin` / `admin`. The
**MoonLightBridge / MoonLightBridge Java ↔ Rust** dashboard shows both metric producers. Open
**Explore**, select **Tempo**, and search recent traces to inspect the Java client span and its Rust
server child span. The shared trace includes `rpc.request_id`, method ID, sizes, result status, and
Rust handler stages. Prometheus is available directly at <http://localhost:9090>.

The applications own their metrics endpoints and OTLP lifecycle. Adding an observability module by
itself never opens a port or sends anonymous product statistics. Stop the local stack with:

```bash
docker compose -f examples/observability/docker-compose.yml down
```
