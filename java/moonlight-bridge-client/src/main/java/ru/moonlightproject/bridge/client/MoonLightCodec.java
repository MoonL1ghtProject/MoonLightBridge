package ru.moonlightproject.bridge.client;

import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Typed request encoder and response decoder for one generated RPC.
 *
 * <p>Generated clients pass a codec to {@link MoonLightChannel#call} so that the channel runs
 * encoding and decoding inside the request observation. Tracing then shows both stages as
 * children of the client span instead of outside it.</p>
 *
 * @param encoder converts the typed request to wire bytes
 * @param decoder converts wire bytes to the typed response
 * @param encodeStage stable stage name, for example {@code protobuf.encode.Service.Method}
 * @param decodeStage stable stage name, for example {@code protobuf.decode.Service.Method}
 * @param <Q> request type
 * @param <R> response type
 */
public record MoonLightCodec<Q, R>(
    Encoder<Q> encoder,
    Decoder<R> decoder,
    String encodeStage,
    String decodeStage
) {
    /** Validates that every part is present. */
    public MoonLightCodec {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(encodeStage, "encodeStage");
        Objects.requireNonNull(decodeStage, "decodeStage");
    }

    /**
     * Creates a Protobuf codec whose stages are named {@code protobuf.encode.<Service>.<Method>}
     * and {@code protobuf.decode.<Service>.<Method>}.
     *
     * @param serviceMethod {@code Service.Method} suffix of the stage names
     * @param encoder request serializer, normally {@code Request::toByteArray}
     * @param decoder response parser, normally {@code Response::parseFrom}
     * @param <Q> request type
     * @param <R> response type
     * @return codec for one generated RPC
     */
    public static <Q, R> MoonLightCodec<Q, R> protobuf(
        String serviceMethod, Encoder<Q> encoder, Decoder<R> decoder
    ) {
        return new MoonLightCodec<>(encoder, decoder,
            "protobuf.encode." + serviceMethod, "protobuf.decode." + serviceMethod);
    }

    /**
     * Encodes a request inside the bundled function observation.
     *
     * @param request typed request
     * @return wire bytes
     */
    public byte[] encode(Q request) {
        return MoonLightTelemetry.traceFunction(encodeStage, () -> encoder.encode(request));
    }

    /**
     * Checks the generated response limit and decodes a response inside the bundled function
     * observation.
     *
     * @param body wire bytes
     * @param policy generated policy whose response limit applies, or {@code null}
     * @return typed response
     * @throws CompletionException when the response is too large or cannot be decoded
     */
    public R decode(byte[] body, RpcPolicy policy) {
        if (policy != null && body.length > policy.maxResponseBytes()) {
            throw new CompletionException(
                new IllegalArgumentException("response exceeds generated RPC policy"));
        }
        try {
            return MoonLightTelemetry.traceFunction(decodeStage, () -> decoder.decode(body));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    /**
     * Request serializer.
     *
     * @param <Q> request type
     */
    @FunctionalInterface
    public interface Encoder<Q> {
        /**
         * Serializes a request.
         *
         * @param request typed request
         * @return wire bytes
         */
        byte[] encode(Q request);
    }

    /**
     * Response parser.
     *
     * @param <R> response type
     */
    @FunctionalInterface
    public interface Decoder<R> {
        /**
         * Parses a response.
         *
         * @param body wire bytes
         * @return typed response
         * @throws Exception when the bytes are not a valid response
         */
        R decode(byte[] body) throws Exception;
    }
}
