# Embedded Paper/Folia SDK

`moonlight-bridge-paper` is a thin adapter over the universal `moonlight-bridge-java` runtime and
is linked into each plugin as a library. Server owners do not
install a separate bridge plugin, and plugin code does not initialize framework telemetry.

Start supervision in `onEnable`; it performs no network I/O on the server
thread and reconnects after backend restarts:

```java
private MoonLightBridge bridge;
private EchoServiceClient backend;

public void onEnable() throws IOException {
    bridge = MoonLightBridge.start(this, "tcp://127.0.0.1:38201");
    backend = new EchoServiceClient(bridge.channel());
    bridge.warmUp(() -> backend.echo(warmupRequest))
        .whenCompleteOnGlobal((reply, error) -> getLogger().info("MoonLightBridge ready"));
}
```

RPC futures finish on transport threads. `PaperCall` makes the target explicit
and schedules Bukkit access on the correct Paper or Folia scheduler:

```java
bridge.call(backend.echo(request)).whenCompleteFor(player, (reply, error) -> {
    if (error == null) player.sendMessage(reply.getMessage());
});

bridge.call(backend.loadChunk(request)).whenCompleteAt(location, (reply, error) -> {
    // Safe access to region-owned state.
});
```

Use `whenCompleteOnGlobal` for console/server-wide state, `whenCompleteAt` for
location-owned state, and `whenCompleteFor` for players/entities/command
senders. Call `bridge.close()` from `onDisable`.

For controlled maintenance, initiate `bridge.drain(timeout)` before shutdown. New calls are
rejected while already accepted unary and streaming work receives a bounded opportunity to finish.
The Paper example exposes unary, batch, typed-event, server-streaming, and drain commands.

Use `bridge.health(timeout)`, `bridge.isConnected()`,
`bridge.pendingRequests()`, and `bridge.lastFailure()` for readiness. Generated
`*Events` helpers subscribe to typed backend events and subscriptions are
restored after reconnect.

Scheduler dispatch intentionally waits for a safe Paper/Folia execution point,
which can add up to one server tick after the RPC has already completed. Measure
transport latency before entering the scheduled callback. Pure Java processing
that does not touch Bukkit state can remain on the original completion stage.

The plugin must include `folia-supported: true` in `plugin.yml` and shade
`moonlight-bridge-paper` with merged `META-INF/services` resources. The example build also
relocates bundled dependencies to prevent classpath conflicts with server forks.

Contracts may be handwritten Protobuf or code-first Java/Kotlin. Both generate the same client API;
the Paper adapter does not require a special schema format. See [codegen.md](codegen.md).

Application monitoring is also independent of Paper. Combine the Micrometer and OpenTelemetry
adapters with the underlying client configuration to export plugin-owned metrics and propagate one
trace through the Java/Paper client, Rust request, and generated decode/handler/encode stages. Give
the Java and Rust processes distinct explicit service names. The adapters never access Bukkit state
or schedule callbacks and therefore do not change Paper/Folia thread-affinity rules. See
[observability.md](observability.md).
