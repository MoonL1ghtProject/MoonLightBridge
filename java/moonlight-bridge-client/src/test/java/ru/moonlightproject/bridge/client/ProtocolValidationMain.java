package ru.moonlightproject.bridge.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletionException;

/** Regression test for response-kind/method validation in the typed pending table. */
public final class ProtocolValidationMain {
    public static void main(String[] args) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            Thread server = Thread.ofPlatform().start(() -> serveInvalidResponse(listener));
            try (MoonLightClient client = MoonLightClient.tcp("127.0.0.1", listener.getLocalPort())) {
                try {
                    client.request(77, new byte[0], Duration.ofSeconds(1)).join();
                    throw new AssertionError("mismatched response unexpectedly succeeded");
                } catch (CompletionException error) {
                    if (!(error.getCause() instanceof java.io.IOException)
                        || !error.getCause().getMessage().contains("does not match pending request")) {
                        throw error;
                    }
                }
            }
            server.join();
        }
        System.out.println("MoonLightBridge typed pending response validation passed");
    }

    private static void serveInvalidResponse(ServerSocket listener) {
        try (var socket = listener.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            readFrame(input);
            writeFrame(output, 17, 0, 0, settings());
            Frame request = readFrame(input);
            writeFrame(output, 2, request.methodId + 1, request.requestId, new byte[0]);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static byte[] settings() {
        return java.nio.ByteBuffer.allocate(16)
            .putInt(8 * 1024 * 1024).putInt(256).putLong(63).array();
    }

    private static Frame readFrame(DataInputStream input) throws Exception {
        if (input.readInt() != 0x4D4C4252) throw new AssertionError("bad magic");
        input.readUnsignedByte();
        int kind = input.readUnsignedByte();
        input.readUnsignedShort();
        int length = input.readInt();
        int methodId = input.readInt();
        long requestId = input.readLong();
        input.readNBytes(length);
        return new Frame(kind, methodId, requestId);
    }

    private static void writeFrame(
        DataOutputStream output, int kind, int methodId, long requestId, byte[] body
    ) throws Exception {
        output.writeInt(0x4D4C4252);
        output.writeByte(1);
        output.writeByte(kind);
        output.writeShort(0);
        output.writeInt(body.length);
        output.writeInt(methodId);
        output.writeLong(requestId);
        output.write(body);
        output.flush();
    }

    private record Frame(int kind, int methodId, long requestId) { }
}
