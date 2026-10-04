package ru.moonlightproject.bridge.client;

import com.github.luben.zstd.Zstd;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded Zstandard compression primitives used by protocol-v2 transports. */
public final class MoonLightCompression {
    private MoonLightCompression() { }

    /** Codec selected for one application payload. */
    public enum Codec {
        /** Payload is transmitted unchanged. */
        NONE(0),
        /** Zstandard frame compression. */
        ZSTD(1);

        private final int wireValue;

        Codec(int wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the stable metadata value.
         *
         * @return unsigned codec identifier
         */
        public int wireValue() {
            return wireValue;
        }

        static Codec fromWire(int value) throws IOException {
            for (Codec codec : values()) if (codec.wireValue == value) return codec;
            throw new IOException("unknown compression codec " + value);
        }
    }

    /**
     * Bounded compression configuration.
     *
     * @param minPayloadBytes smallest payload considered for compression
     * @param minSavingsBytes minimum savings required to retain compressed output
     * @param maxDecodedBodyLength largest decoded payload accepted
     * @param maxExpansionRatio largest decoded-to-encoded byte ratio
     * @param compressionLevel Zstandard compression level
     */
    public record Options(
        int minPayloadBytes,
        int minSavingsBytes,
        int maxDecodedBodyLength,
        int maxExpansionRatio,
        int compressionLevel
    ) {
        /** Validates non-negative thresholds and positive safety limits. */
        public Options {
            if (minPayloadBytes < 0 || minSavingsBytes < 0
                || maxDecodedBodyLength < 1 || maxExpansionRatio < 1) {
                throw new IllegalArgumentException("invalid compression options");
            }
        }

        /**
         * Returns conservative production defaults.
         *
         * @return 1 KiB threshold, 64-byte savings, 16 MiB decoded limit, and ratio 64
         */
        public static Options defaults() {
            return new Options(1024, 64, 16 * 1024 * 1024, 64, 1);
        }
    }

    /**
     * Immutable result of optional payload compression.
     */
    public static final class Encoded {
        private final Codec codec;
        private final int originalLength;
        private final byte[] bytes;

        /**
         * Creates a defensively copied encoded payload.
         *
         * @param codec codec used for the bytes
         * @param originalLength original decoded byte count
         * @param bytes encoded bytes
         */
        public Encoded(Codec codec, int originalLength, byte[] bytes) {
            this(codec, originalLength, bytes, true);
        }

        private Encoded(Codec codec, int originalLength, byte[] bytes, boolean copy) {
            this.codec = Objects.requireNonNull(codec, "codec");
            this.bytes = copy ? Objects.requireNonNull(bytes, "bytes").clone()
                : Objects.requireNonNull(bytes, "bytes");
            if (originalLength < 0) throw new IllegalArgumentException("negative original length");
            this.originalLength = originalLength;
        }

        /**
         * Returns the codec actually used.
         *
         * @return selected codec
         */
        public Codec codec() {
            return codec;
        }

        /**
         * Returns the decoded byte count.
         *
         * @return original payload length
         */
        public int originalLength() {
            return originalLength;
        }

        /**
         * Returns a copy of the encoded bytes.
         *
         * @return defensive byte-array copy
         */
        public byte[] bytes() {
            return bytes.clone();
        }

        byte[] internalBytes() {
            return bytes;
        }
    }

    static Encoded transportEncoded(Codec codec, int originalLength, byte[] bytes) {
        return new Encoded(codec, originalLength, bytes, false);
    }

    /**
     * Applies negotiated compression while retaining the original bytes when compression is not
     * useful or would exceed the configured expansion-ratio contract.
     *
     * @param payload application payload
     * @param codec negotiated codec
     * @param options bounded compression options
     * @return immutable encoded payload
     * @throws IOException when compression fails or the input exceeds the decoded limit
     */
    public static Encoded encode(byte[] payload, Codec codec, Options options) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        if (payload.length > options.maxDecodedBodyLength) {
            throw new IOException("payload exceeds decoded body limit");
        }
        if (codec == Codec.NONE || payload.length < options.minPayloadBytes) {
            return new Encoded(Codec.NONE, payload.length, payload, false);
        }

        final byte[] compressed;
        try {
            compressed = Zstd.compress(payload, options.compressionLevel);
        } catch (RuntimeException | LinkageError error) {
            throw new IOException("zstd compression failed", error);
        }
        boolean worthwhile = (long) compressed.length + options.minSavingsBytes <= payload.length;
        boolean withinRatio = payload.length
            <= (long) compressed.length * options.maxExpansionRatio;
        return worthwhile && withinRatio
            ? new Encoded(codec, payload.length, compressed, false)
            : new Encoded(Codec.NONE, payload.length, payload, false);
    }

    /**
     * Decodes one payload after checking its size, ratio, and shared byte budget.
     *
     * @param encoded immutable payload and announced decoded length
     * @param options bounded decompression options
     * @param budget process-shared decoded-byte budget
     * @return decoded payload
     * @throws IOException when validation or decompression fails
     */
    public static byte[] decode(
        Encoded encoded, Options options, DecodedByteBudget budget
    ) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(budget, "budget");
        if (encoded.originalLength > options.maxDecodedBodyLength) {
            throw new IOException("announced decoded length exceeds limit");
        }
        if (encoded.codec == Codec.NONE) {
            if (encoded.bytes.length != encoded.originalLength) {
                throw new IOException("uncompressed payload length mismatch");
            }
            return encoded.bytes.clone();
        }
        if (encoded.originalLength
            > (long) encoded.bytes.length * options.maxExpansionRatio) {
            throw new IOException("announced decoded-to-encoded ratio exceeds limit");
        }
        try (Reservation ignored = budget.reserve(encoded.originalLength)) {
            final byte[] decoded;
            try {
                decoded = Zstd.decompress(encoded.bytes, encoded.originalLength);
            } catch (RuntimeException | LinkageError error) {
                throw new IOException("corrupt zstd payload", error);
            }
            if (decoded.length != encoded.originalLength) {
                throw new IOException("decoded payload length mismatch");
            }
            return decoded;
        }
    }

    /** Process-shared decoded-byte admission budget. */
    public static final class DecodedByteBudget {
        private final int capacity;
        private final AtomicInteger used = new AtomicInteger();

        /**
         * Creates a positive byte budget.
         *
         * @param capacity maximum simultaneous reservation in bytes
         */
        public DecodedByteBudget(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("budget must be positive");
            this.capacity = capacity;
        }

        /**
         * Reserves bytes until the returned handle is closed.
         *
         * @param requested requested decoded bytes
         * @return closeable reservation
         * @throws IOException when insufficient capacity remains
         */
        public Reservation reserve(int requested) throws IOException {
            if (requested < 0) throw new IllegalArgumentException("negative reservation");
            int observed = used.get();
            while (true) {
                int available = capacity - observed;
                if (requested > available) {
                    throw new IOException("decoded byte budget exhausted");
                }
                int updated = Math.addExact(observed, requested);
                if (used.compareAndSet(observed, updated)) {
                    return new Reservation(used, requested);
                }
                observed = used.get();
            }
        }
    }

    /** Closeable reservation from a shared decoded-byte budget. */
    public static final class Reservation implements AutoCloseable {
        private final AtomicInteger used;
        private final int reserved;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(AtomicInteger used, int reserved) {
            this.used = used;
            this.reserved = reserved;
        }

        /** Releases this reservation exactly once. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) used.addAndGet(-reserved);
        }
    }
}
