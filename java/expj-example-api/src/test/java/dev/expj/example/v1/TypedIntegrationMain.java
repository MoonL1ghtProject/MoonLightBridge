package dev.expj.example.v1;

import dev.expj.client.ExpjClient;

public final class TypedIntegrationMain {
    public static void main(String[] args) throws Exception {
        try (var channel = ExpjClient.connect("tcp://127.0.0.1:38191")) {
            var client = new EchoServiceClient(channel);
            EchoResponse response = client.echo(EchoRequest.newBuilder()
                .setMessage("typed-protobuf")
                .build()).join();
            if (!response.getMessage().equals("typed-protobuf")) throw new AssertionError(response);
            var requests = java.util.stream.IntStream.range(0, 128)
                .mapToObj(index -> EchoRequest.newBuilder().setMessage("batch-" + index).build())
                .toList();
            var responses = client.echoBatch(requests).join();
            for (int index = 0; index < responses.size(); index++) {
                if (!responses.get(index).getMessage().equals("batch-" + index)) throw new AssertionError(index);
            }
            System.out.println("EXPJ typed Protobuf integration passed, including 128-call batch");
        }
    }
}
