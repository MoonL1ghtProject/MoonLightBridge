package ru.moonlightproject.bridge.examples.paper;

import ru.moonlightproject.bridge.client.MoonLightClient;
import ru.moonlightproject.bridge.example.v1.BackendNoticeEvent;
import ru.moonlightproject.bridge.example.v1.EchoEvents;
import ru.moonlightproject.bridge.example.v1.EchoRequest;
import ru.moonlightproject.bridge.example.v1.EchoResponse;
import ru.moonlightproject.bridge.example.v1.EchoServiceClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public final class BackendSmokeMain {
    public static void main(String[] args) throws Exception {
        try (var channel = MoonLightClient.connect(args[0])) {
            var backend = new EchoServiceClient(channel);
            var notice = new CompletableFuture<BackendNoticeEvent>();
            try (var subscription = EchoEvents.onBackendNoticeEvent(channel, notice::complete)) {
            var single = backend.echo(EchoRequest.newBuilder().setMessage("smoke").build()).join();
            if (!single.getMessage().contains("SMOKE")) {
                throw new AssertionError("unexpected Rust response: " + single.getMessage());
            }
            if (!notice.orTimeout(1, TimeUnit.SECONDS).join().getMessage().contains("SMOKE")) {
                throw new AssertionError("typed backend event was not delivered");
            }
            List<EchoRequest> batch = IntStream.range(0, 32)
                .mapToObj(index -> EchoRequest.newBuilder().setMessage("batch-" + index).build())
                .toList();
            var responses = backend.echoBatch(batch).join();
            if (responses.size() != batch.size()) {
                throw new AssertionError("batch response count mismatch");
            }
            var streamed = new CompletableFuture<List<String>>();
            var items = new ArrayList<String>();
            backend.streamEcho(EchoRequest.newBuilder().setMessage("paper-stream").build())
                .subscribe(new Flow.Subscriber<EchoResponse>() {
                    public void onSubscribe(Flow.Subscription value) { value.request(3); }
                    public void onNext(EchoResponse value) { items.add(value.getMessage()); }
                    public void onError(Throwable error) { streamed.completeExceptionally(error); }
                    public void onComplete() { streamed.complete(List.copyOf(items)); }
                });
            if (!streamed.orTimeout(1, TimeUnit.SECONDS).join().equals(List.of(
                    "paper-stream-0", "paper-stream-1", "paper-stream-2"))) {
                throw new AssertionError("server stream mismatch: " + items);
            }
            }
            channel.drain(Duration.ofSeconds(1)).completion().toCompletableFuture().join();
            System.out.println(
                "MoonLightBridge Paper example smoke test passed: unary, batch, event, stream, drain");
        }
    }
}
