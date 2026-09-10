# MoonLightBridge Paper load-test plugin

This separate plugin generates bounded load without blocking the Minecraft
thread or scheduling one Bukkit callback per RPC. It reports end-to-end RPC
p50/p95/p99/max latency, throughput, and failures after one warm-up phase.

```text
/moonlightload [requests=10000] [concurrency=128] [payload-bytes=64]
```

Limits are one million requests, concurrency 256, and a 64 KiB message. Run it
only on a test server. Build the shaded Java 21 JAR with:

```bash
./gradlew :examples:paper-load-test-plugin:shadowJar
```
