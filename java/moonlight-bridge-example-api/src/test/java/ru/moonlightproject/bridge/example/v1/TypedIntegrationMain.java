package ru.moonlightproject.bridge.example.v1;

import ru.moonlightproject.bridge.client.MoonLightClient;

public final class TypedIntegrationMain {
    public static void main(String[] args) throws Exception {
        try (var channel = MoonLightClient.connect("tcp://127.0.0.1:38191")) {
            var client = new EchoServiceClient(channel);
            var event = new java.util.concurrent.CompletableFuture<BackendNoticeEvent>();
            try (var subscription = EchoEvents.onBackendNoticeEvent(channel, event::complete)) {
            EchoResponse response = client.echo(EchoRequest.newBuilder()
                .setMessage("typed-protobuf")
                .build()).join();
            if (!response.getMessage().equals("typed-protobuf")) throw new AssertionError(response);
            if (!event.orTimeout(1, java.util.concurrent.TimeUnit.SECONDS).join().getMessage()
                .equals("processed:typed-protobuf")) throw new AssertionError("typed event missing");
            var requests = java.util.stream.IntStream.range(0, 128)
                .mapToObj(index -> EchoRequest.newBuilder().setMessage("batch-" + index).build())
                .toList();
            var responses = client.echoBatch(requests).join();
            for (int index = 0; index < responses.size(); index++) {
                if (!responses.get(index).getMessage().equals("batch-" + index)) throw new AssertionError(index);
            }
            System.out.println("MoonLightBridge typed Protobuf integration passed, including event and 128-call batch");
            }
        }
    }
}
