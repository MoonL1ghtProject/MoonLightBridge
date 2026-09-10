# Embedded Paper/Folia SDK

MoonLightBridge is linked into each plugin as a library. Server owners do not
install a separate bridge plugin, and plugin code does not initialize Sentry.

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

Use `bridge.health(timeout)`, `bridge.isConnected()`,
`bridge.pendingRequests()`, and `bridge.lastFailure()` for readiness. Generated
`*Events` helpers subscribe to typed backend events and subscriptions are
restored after reconnect.

Scheduler dispatch intentionally waits for a safe Paper/Folia execution point,
which can add up to one server tick after the RPC has already completed. Measure
transport latency before entering the scheduled callback. Pure Java processing
that does not touch Bukkit state can remain on the original completion stage.

The plugin must include `folia-supported: true` in `plugin.yml` and shade the
framework with merged `META-INF/services` resources. The example build also
relocates Protobuf and Sentry to prevent classpath conflicts with server forks.
