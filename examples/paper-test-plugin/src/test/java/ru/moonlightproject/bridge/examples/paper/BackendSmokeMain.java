package ru.moonlightproject.bridge.examples.paper;

import ru.moonlightproject.bridge.client.MoonLightClient;
import ru.moonlightproject.bridge.example.v1.EchoRequest;
import ru.moonlightproject.bridge.example.v1.EchoServiceClient;
import java.util.List;
import java.util.stream.IntStream;

public final class BackendSmokeMain {
    public static void main(String[] args) throws Exception {
        try (var channel = MoonLightClient.connect(args[0])) {
            var backend = new EchoServiceClient(channel);
            var single = backend.echo(EchoRequest.newBuilder().setMessage("smoke").build()).join();
            if (!single.getMessage().contains("SMOKE")) {
                throw new AssertionError("unexpected Rust response: " + single.getMessage());
            }
            List<EchoRequest> batch = IntStream.range(0, 32)
                .mapToObj(index -> EchoRequest.newBuilder().setMessage("batch-" + index).build())
                .toList();
            var responses = backend.echoBatch(batch).join();
            if (responses.size() != batch.size()) {
                throw new AssertionError("batch response count mismatch");
            }
            System.out.println("MoonLightBridge Paper example smoke test passed: typed unary + 32-call batch");
        }
    }
}
