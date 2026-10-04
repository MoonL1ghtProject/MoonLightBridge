# Universal Java SDK

`moonlight-bridge-java` is the recommended runtime dependency for Java 21 and newer. It has no
Minecraft API dependency and can be embedded in services, command-line tools, proxies, desktop
applications, or any other JVM process. `moonlight-bridge-paper` builds on this same runtime and
only adds scheduler-safe Minecraft callbacks.

## Installation

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("ru.moonlightproject:moonlight-bridge-java:0.4.0")
}
```

Generated Protobuf clients compile against `moonlight-bridge-client`. Applications normally do not
declare it separately because the universal runtime exposes it transitively.

## Starting and stopping

`MoonLightBridge.start(endpoint)` validates the endpoint and returns without waiting for network
I/O. A supervisor connects in the background and reconnects with bounded exponential backoff. Calls
made while disconnected fail immediately; interrupted calls are not replayed automatically because
the backend may already have committed a mutation.

```java
import ru.moonlightproject.bridge.MoonLightBridge;

public final class BackendGateway implements AutoCloseable {
    private final MoonLightBridge bridge;
    private final ProfileServiceClient profiles;

    public BackendGateway(String endpoint) throws IOException {
        bridge = MoonLightBridge.start(endpoint);
        profiles = new ProfileServiceClient(bridge.channel());
    }

    public CompletionStage<Profile> load(String playerId) {
        var request = LoadProfileRequest.newBuilder().setPlayerId(playerId).build();
        return profiles.loadProfile(request);
    }

    @Override
    public void close() throws IOException {
        bridge.close();
    }
}
```

Use `MoonLightBridge.connect(endpoint)` when startup must wait for the first connection. Both
factories supervise later disconnects. Create one bridge per endpoint and close it at shutdown.

For multiple independent backends, use explicit names instead of hiding routing behind method IDs:

```java
try (var backends = MoonLightBridgeGroup.start(Map.of(
    "profiles", "tcp://127.0.0.1:38191",
    "machines", "unix:/run/moonlightbridge/machines.sock"
))) {
    var profiles = new ProfileServiceClient(backends.channel("profiles"));
    var machines = new MachineServiceClient(backends.channel("machines"));
}
```

Each member has its own physical connection, bounded queues, health, reconnect state, and failure
domain. A disconnected backend does not reroute or replay work through another member.

## Endpoint forms

| Endpoint | Intended topology |
|---|---|
| `unix:/run/moonlightbridge/backend.sock` | Same Linux host with a shared filesystem mount |
| `tcp://127.0.0.1:38191` | Local development or trusted private boundary |
| `tls://backend.internal:38191` | Different containers, hosts, or trust domains using mTLS |

Pterodactyl containers do not share `127.0.0.1`. Use a shared Unix socket or the backend container's
private network address. See the [deployment guide](deployment-pterodactyl.md).

## Lifecycle and health

- `firstConnection()` completes after the first successful HELLO/WELCOME handshake.
- `isConnected()` reports whether a physical connection is currently ready.
- `lastFailure()` returns the latest connection error for diagnostics.
- `pendingRequests()` reports calls awaiting a response on the active connection.
- `ping(timeout)` checks transport liveness.
- `health(timeout)` returns backend readiness, uptime, connections, requests, and limits.
- `warmUp(operation)` runs an asynchronous codec/JIT warm-up after the first connection.
- `close()` stops reconnects, closes the socket, and fails pending work.

Application code does not initialize or configure the framework's internal telemetry provider.
Payload bodies are never attached to operational events.

## Deadlines, batches, and errors

Generated methods return `CompletableFuture<Response>`. `withDeadline(duration)` creates a client
view whose deadline can only tighten the limit declared by that method's `.proto` policy. Generated
clients expose immutable `METHOD_POLICY` constants with timeout, retry safety, request and response
limits, required scopes, compression preference, and trace sampling rate. The hot request path uses
these constants directly and does not perform Protobuf reflection. Generated `methodBatch(requests)`
methods preserve request order while responses complete independently over the multiplexed
connection.

`COMPRESSION_MODE_REQUIRED` fails the call before sending when Zstandard was not negotiated;
`COMPRESSION_MODE_DISABLED` sends that method without compression. `PREFER` and `DEFAULT` retain the
negotiated size and savings thresholds. Generated clients validate decoded message limits on both
sides of the call.

Batch size is not fixed at 32. The writer coalesces frames already available, up to active frame and
byte limits, and sends immediately when a burst reaches those limits. It does not intentionally wait
to fill a batch. Prefer a domain-level batch RPC when Rust can process a group more efficiently.

Transport failures complete futures exceptionally. A backend `HandlerError` becomes
`MoonLightRemoteException` with a stable transport `ErrorCode`. Model business failures in Protobuf
instead of parsing diagnostic error text.

## Server events

Every top-level Protobuf message ending in `Event` receives a generated subscription helper:

```java
AutoCloseable subscription = ProfileEvents.onBalanceChangedEvent(
    bridge.channel(),
    event -> cache.put(event.getPlayerId(), event.getBalance())
);

subscription.close();
```

Subscriptions survive reconnects, but events are best-effort and not persisted. Resynchronize
important state after reconnect rather than treating events as a durable queue.

## Server-streaming RPC

Declare `returns (stream Result)` in Protobuf. The generated Java method returns a standard
`Flow.Publisher<Result>`. Demand is sent to Rust as wire-level credit, so Rust produces only what
the subscriber has requested:

```java
client.scanChunks(request).subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;
    public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    public void onNext(ChunkResult value) { process(value); subscription.request(1); }
    public void onError(Throwable error) { report(error); }
    public void onComplete() { finished(); }
});
```

Cancellation, the configured deadline, connection loss, and remote errors terminate the publisher.
Streams interrupted by reconnect are never replayed automatically.

## Low-level module

`moonlight-bridge-client` exposes direct connections, reconnect supervision, performance profiles,
raw byte requests, metrics, and telemetry SPIs. It is intended for generated code, framework
adapters, and advanced integrations. Applications should normally use `moonlight-bridge-java` so
lifecycle and defaults remain consistent.
