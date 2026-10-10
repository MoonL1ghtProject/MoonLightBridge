package ru.moonlightproject.bridge.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletionException;

/** Regression test for response-kind/method validation in the typed pending table. */
public final class ProtocolValidationMain {
    public static void main(String[] args) throws Exception {
        verifyProtocolV2MetadataGoldenVector();
        verifyRequestMetadataFastPathGoldenVector();
        verifyUserMetadataValidation();
        verifyMalformedMetadataIsRejected();
        verifyV2ErrorCodes();
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

    private static void verifyProtocolV2MetadataGoldenVector() throws Exception {
        MoonLightMetadata metadata = MoonLightMetadata.builder()
            .put("x-region", "eu".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .putReserved(MoonLightMetadata.ReservedKey.DEADLINE_MILLIS,
                ByteBuffer.allocate(4).putInt(100).array())
            .build();
        byte[] expected = HexFormat.of().parseHex(Files.readString(
            sharedVectorPath()).trim());
        if (!Arrays.equals(metadata.encodePrefix(), expected)) {
            throw new AssertionError("Java metadata encoding differs from the shared v2 vector");
        }
        byte[] framed = Arrays.copyOf(expected, expected.length + 3);
        framed[expected.length] = 1;
        framed[expected.length + 1] = 2;
        framed[expected.length + 2] = 3;
        MoonLightMetadata.Decoded decoded = MoonLightMetadata.decode(
            framed, MoonLightMetadata.Limits.defaults());
        if (!Arrays.equals(decoded.payload(), new byte[] {1, 2, 3})
            || !Arrays.equals(decoded.metadata().get("x-region"), new byte[] {'e', 'u'})) {
            throw new AssertionError("Java metadata decoder did not preserve metadata/payload");
        }
    }

    private static void verifyRequestMetadataFastPathGoldenVector() {
        byte[] traceId = new byte[16];
        byte[] parentSpanId = new byte[8];
        for (int index = 0; index < traceId.length; index++) traceId[index] = (byte) index;
        for (int index = 0; index < parentSpanId.length; index++) {
            parentSpanId[index] = (byte) (index + 16);
        }
        MoonLightMetadata metadata = MoonLightMetadata.builder()
            .put("x-region", new byte[] {'e', 'u'})
            .build();
        byte[] encoded = metadata.encodeRequestPrefix(
            100,
            new MoonLightTraceContext(traceId, parentSpanId, true),
            MoonLightCompression.Codec.ZSTD,
            4096);
        byte[] expected = HexFormat.of().parseHex(
            "0000004f"
                + "8001000000000400000064"
                + "80020000000019000102030405060708090a0b0c0d0e0f101112131415161701"
                + "8006000000000101"
                + "8007000000000400001000"
                + "7fff0800000002782d726567696f6e6575");
        if (!Arrays.equals(encoded, expected)) {
            throw new AssertionError("request metadata fast path changed canonical wire encoding");
        }
        ByteBuffer direct = ByteBuffer.allocate(expected.length + 2);
        direct.put((byte) 0x55);
        int prefixLength = metadata.requestPrefixLength(
            new MoonLightTraceContext(traceId, parentSpanId, true),
            MoonLightCompression.Codec.ZSTD);
        metadata.writeRequestPrefix(
            direct,
            prefixLength,
            100,
            new MoonLightTraceContext(traceId, parentSpanId, true),
            MoonLightCompression.Codec.ZSTD,
            4096);
        direct.put((byte) 0x66);
        if (prefixLength != expected.length
            || direct.position() != expected.length + 2
            || direct.array()[0] != (byte) 0x55
            || direct.array()[direct.array().length - 1] != (byte) 0x66
            || !Arrays.equals(Arrays.copyOfRange(direct.array(), 1, expected.length + 1), expected)) {
            throw new AssertionError("request metadata cannot be encoded into an existing frame");
        }
        if (MoonLightMetadata.empty() != MoonLightMetadata.empty()) {
            throw new AssertionError("empty request metadata is not shared");
        }
        byte[] emptyEncoded = MoonLightMetadata.empty().encodeRequestPrefix(
            100, null, MoonLightCompression.Codec.NONE, 0);
        byte[] emptyExpected = HexFormat.of().parseHex("0000000b8001000000000400000064");
        if (!Arrays.equals(emptyEncoded, emptyExpected)) {
            throw new AssertionError("empty request metadata fast path changed wire encoding");
        }
    }

    private static void verifyUserMetadataValidation() {
        expectIllegalArgument(() -> MoonLightMetadata.builder().put("Uppercase", new byte[0]));
        expectIllegalArgument(() -> MoonLightMetadata.builder().put("moonlight-deadline", new byte[0]));
        MoonLightMetadata.Builder builder = MoonLightMetadata.builder()
            .putReserved(MoonLightMetadata.ReservedKey.DEADLINE_MILLIS, new byte[4]);
        expectIllegalArgument(() -> builder.putReserved(
            MoonLightMetadata.ReservedKey.DEADLINE_MILLIS, new byte[4]));
    }

    private static void verifyMalformedMetadataIsRejected() {
        byte[] duplicateDeadline = {
            0, 0, 0, 18,
            (byte) 0x80, 1, 0, 0, 0, 0, 2, 0, 1,
            (byte) 0x80, 1, 0, 0, 0, 0, 2, 0, 2
        };
        expectIOException(() -> MoonLightMetadata.decode(
            duplicateDeadline, MoonLightMetadata.Limits.defaults()));
        byte[] unknownCritical = {0, 0, 0, 7, (byte) 0x80, 64, 0, 0, 0, 0, 0};
        expectIOException(() -> MoonLightMetadata.decode(
            unknownCritical, MoonLightMetadata.Limits.defaults()));
        byte[] ignoredKeyOutOfOrder = {
            0, 0, 0, 14,
            0, 64, 0, 0, 0, 0, 0,
            (byte) 0x80, 1, 0, 0, 0, 0, 0
        };
        expectIOException(() -> MoonLightMetadata.decode(
            ignoredKeyOutOfOrder, MoonLightMetadata.Limits.defaults()));
        byte[] invalidUserKey = {
            0, 0, 0, 16, 0x7f, (byte) 0xff, 9, 0, 0, 0, 0,
            'U', 'p', 'p', 'e', 'r', 'c', 'a', 's', 'e'
        };
        expectIOException(() -> MoonLightMetadata.decode(
            invalidUserKey, MoonLightMetadata.Limits.defaults()));
    }

    private static void verifyV2ErrorCodes() {
        MoonLightClient.ErrorCode[] expected = {
            MoonLightClient.ErrorCode.UNAUTHENTICATED,
            MoonLightClient.ErrorCode.PERMISSION_DENIED,
            MoonLightClient.ErrorCode.UNAVAILABLE,
            MoonLightClient.ErrorCode.COMPRESSION_FAILURE,
            MoonLightClient.ErrorCode.REPLAY_GAP,
            MoonLightClient.ErrorCode.FAILED_PRECONDITION,
            MoonLightClient.ErrorCode.UNSUPPORTED_PROTOCOL
        };
        for (int index = 0; index < expected.length; index++) {
            if (MoonLightClient.ErrorCode.fromWire(index + 7) != expected[index]) {
                throw new AssertionError("unstable v2 error code " + (index + 7));
            }
        }
    }

    private static void expectIllegalArgument(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("invalid metadata unexpectedly accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static Path sharedVectorPath() throws java.io.IOException {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("testdata/protocol-v2/metadata-deadline-region.hex");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new java.io.IOException("shared protocol-v2 vector is unavailable");
    }

    private static void expectIOException(IoOperation operation) {
        try {
            operation.run();
            throw new AssertionError("malformed metadata unexpectedly accepted");
        } catch (java.io.IOException expected) {
            // Expected.
        } catch (Exception unexpected) {
            throw new AssertionError("metadata decoder used the wrong failure type", unexpected);
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
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
        return ByteBuffer.allocate(44)
            .putInt(8 * 1024 * 1024)
            .putInt(16 * 1024 * 1024)
            .putInt(16 * 1024)
            .putInt(256)
            .putInt(64)
            .putInt(32)
            .putInt(1)
            .putLong(63)
            .putLong(0)
            .array();
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
        output.writeByte(2);
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
