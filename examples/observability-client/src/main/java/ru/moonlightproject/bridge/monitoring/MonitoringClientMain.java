package ru.moonlightproject.bridge.monitoring;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import ru.moonlightproject.bridge.client.MoonLightClient;
import ru.moonlightproject.bridge.client.MoonLightPerformanceOptions;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;
import ru.moonlightproject.bridge.micrometer.MoonLightMicrometerTelemetry;
import ru.moonlightproject.bridge.monitoring.v1.MonitoringServiceClient;
import ru.moonlightproject.bridge.monitoring.v1.PingRequest;
import ru.moonlightproject.bridge.otel.MoonLightOtlp;

/** Generates traffic and exposes JVM-side metrics for the Grafana example. */
public final class MonitoringClientMain {
    private MonitoringClientMain() { }

    public static void main(String[] args) throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var micrometer = new MoonLightMicrometerTelemetry(registry);
        String otlpEndpoint = System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        MoonLightOtlp otlp = otlpEndpoint == null || otlpEndpoint.isBlank()
            ? null : MoonLightOtlp.create(otlpEndpoint, 1_024);
        MoonLightTelemetry telemetry = otlp == null
            ? micrometer : MoonLightTelemetry.composite(micrometer, otlp.telemetry());
        var metricsServer = metricsServer(registry);
        String endpoint = System.getenv().getOrDefault(
            "MOONLIGHT_BRIDGE_ENDPOINT", "tcp://127.0.0.1:38192");
        try (var channel = MoonLightClient.connect(
                endpoint, MoonLightPerformanceOptions.automatic("tcp"), telemetry);
             var ignored = otlp) {
            var client = new MonitoringServiceClient(channel);
            long sequence = 0;
            while (!Thread.currentThread().isInterrupted()) {
                var response = client.ping(PingRequest.newBuilder()
                    .setMessage("sample-" + sequence++)
                    .build()).join();
                System.out.println(response.getMessage());
                Thread.sleep(250);
            }
        } finally {
            metricsServer.stop(0);
        }
    }

    private static HttpServer metricsServer(PrometheusMeterRegistry registry) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 9899), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Java Prometheus metrics: http://127.0.0.1:9899/metrics");
        return server;
    }
}
