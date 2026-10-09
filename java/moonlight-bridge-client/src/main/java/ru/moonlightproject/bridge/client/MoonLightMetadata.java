package ru.moonlightproject.bridge.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, bounded protocol-v2 metadata stored before a frame payload. */
public final class MoonLightMetadata {
    private static final int CRITICAL_BIT = 1 << 15;
    private static final int KEY_MASK = CRITICAL_BIT - 1;
    private static final int USER_KEY_ID = 0x7fff;
    private static final int ENTRY_HEADER_LENGTH = 7;

    private final Map<WireKey, byte[]> entries;

    private MoonLightMetadata(Map<WireKey, byte[]> entries) {
        Map<WireKey, byte[]> copy = new TreeMap<>();
        entries.forEach((key, value) -> copy.put(key, value.clone()));
        this.entries = Collections.unmodifiableMap(copy);
    }

    /**
     * Creates an empty metadata builder.
     *
     * @return mutable builder that produces immutable metadata
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a defensive copy of one user metadata value.
     *
     * @param name lowercase ASCII user key
     * @return copied value, or {@code null} when absent
     */
    public byte[] get(String name) {
        byte[] value = entries.get(WireKey.user(name));
        return value == null ? null : value.clone();
    }

    byte[] get(ReservedKey key) {
        byte[] value = entries.get(WireKey.reserved(key));
        return value == null ? null : value.clone();
    }

    void copyInto(Builder builder) {
        entries.forEach((key, value) -> builder.put(key, value));
    }

    /**
     * Encodes the canonical metadata block, including its four-byte length prefix.
     *
     * @return newly allocated wire representation
     */
    public byte[] encodePrefix() {
        int encodedLength = 0;
        for (Map.Entry<WireKey, byte[]> entry : entries.entrySet()) {
            encodedLength = Math.addExact(encodedLength, ENTRY_HEADER_LENGTH);
            encodedLength = Math.addExact(encodedLength, entry.getKey().name.length());
            encodedLength = Math.addExact(encodedLength, entry.getValue().length);
        }
        ByteBuffer output = ByteBuffer.allocate(Math.addExact(Integer.BYTES, encodedLength));
        output.putInt(encodedLength);
        entries.forEach((key, value) -> {
            int taggedId = key.reserved == null ? USER_KEY_ID : CRITICAL_BIT | key.reserved.wireId;
            byte[] name = key.name.getBytes(StandardCharsets.US_ASCII);
            output.putShort((short) taggedId);
            output.put((byte) name.length);
            output.putInt(value.length);
            output.put(name);
            output.put(value);
        });
        return output.array();
    }

    /**
     * Decodes one bounded metadata prefix and copies the remaining payload.
     *
     * @param input complete metadata prefix followed by an optional payload
     * @param limits local limits applied before allocating entry values
     * @return decoded immutable metadata and payload
     * @throws IOException when the prefix is malformed, non-canonical, or exceeds a limit
     */
    public static Decoded decode(byte[] input, Limits limits) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(limits, "limits");
        if (input.length < Integer.BYTES) throw new IOException("truncated metadata length");
        ByteBuffer source = ByteBuffer.wrap(input);
        long announced = Integer.toUnsignedLong(source.getInt());
        int available = source.remaining();
        if (announced > available) throw new IOException("metadata length exceeds frame body");
        if (announced > limits.maxBytes) throw new IOException("metadata exceeds byte limit");
        int blockEnd = Integer.BYTES + (int) announced;
        Map<WireKey, byte[]> decoded = new TreeMap<>();
        int previousKeyId = -1;
        byte[] previousName = null;
        int count = 0;
        while (source.position() < blockEnd) {
            if (++count > limits.maxEntries) throw new IOException("metadata exceeds entry limit");
            if (blockEnd - source.position() < ENTRY_HEADER_LENGTH) {
                throw new IOException("truncated metadata entry header");
            }
            int taggedId = Short.toUnsignedInt(source.getShort());
            boolean critical = (taggedId & CRITICAL_BIT) != 0;
            int keyId = taggedId & KEY_MASK;
            int nameLength = Byte.toUnsignedInt(source.get());
            long valueLength = Integer.toUnsignedLong(source.getInt());
            long entryEnd = (long) source.position() + nameLength + valueLength;
            if (entryEnd > blockEnd) throw new IOException("metadata entry exceeds block");
            if (valueLength > limits.maxValueBytes) throw new IOException("metadata value exceeds limit");
            byte[] nameBytes = new byte[nameLength];
            source.get(nameBytes);
            byte[] value = new byte[(int) valueLength];
            source.get(value);
            if (previousName != null) {
                int order = Integer.compare(previousKeyId, keyId);
                if (order == 0) order = Arrays.compareUnsigned(previousName, nameBytes);
                if (order >= 0) {
                    throw new IOException(order == 0
                        ? "duplicate singleton metadata key" : "non-canonical metadata order");
                }
            }
            previousKeyId = keyId;
            previousName = nameBytes;

            WireKey key;
            if (keyId == USER_KEY_ID) {
                String name = new String(nameBytes, StandardCharsets.US_ASCII);
                try {
                    validateUserKey(name);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid user metadata key", error);
                }
                key = WireKey.user(name);
            } else {
                if (nameLength != 0) throw new IOException("reserved metadata key has a name");
                ReservedKey reserved = ReservedKey.fromWire(keyId);
                if (reserved == null) {
                    if (critical) throw new IOException("unknown critical metadata key " + keyId);
                    continue;
                }
                key = WireKey.reserved(reserved);
            }
            decoded.put(key, value);
        }
        byte[] payload = Arrays.copyOfRange(input, blockEnd, input.length);
        return new Decoded(new MoonLightMetadata(decoded), payload);
    }

    private static void validateUserKey(String name) {
        if (name.isEmpty() || name.length() > 255 || name.startsWith("moonlight-")) {
            throw new IllegalArgumentException("invalid or reserved metadata key");
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (!((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')
                || value == '-' || value == '_' || value == '.')) {
                throw new IllegalArgumentException("metadata key must be lowercase ASCII");
            }
        }
    }

    /** Builder for immutable user metadata. */
    public static final class Builder {
        private final Map<WireKey, byte[]> entries = new LinkedHashMap<>();

        private Builder() { }

        /**
         * Adds one user entry. User code cannot use the reserved {@code moonlight-} namespace.
         *
         * @param name lowercase ASCII key
         * @param value opaque value copied immediately
         * @return this builder
         * @throws IllegalArgumentException for an invalid, reserved, or duplicate key
         */
        public Builder put(String name, byte[] value) {
            validateUserKey(Objects.requireNonNull(name, "name"));
            return put(WireKey.user(name), value);
        }

        Builder putReserved(ReservedKey key, byte[] value) {
            return put(WireKey.reserved(Objects.requireNonNull(key, "key")), value);
        }

        private Builder put(WireKey key, byte[] value) {
            Objects.requireNonNull(value, "value");
            if (entries.putIfAbsent(key, value.clone()) != null) {
                throw new IllegalArgumentException("duplicate singleton metadata key");
            }
            return this;
        }

        /**
         * Freezes the current entries into an immutable value.
         *
         * @return immutable metadata with defensive copies
         */
        public MoonLightMetadata build() {
            return new MoonLightMetadata(entries);
        }
    }

    /** Runtime-owned metadata keys with stable protocol-v2 identifiers. */
    public enum ReservedKey {
        /** Relative request deadline in milliseconds. */
        DEADLINE_MILLIS(1),
        /** Vendor-neutral distributed trace context. */
        TRACE_CONTEXT(2),
        /** Stable idempotency key. */
        IDEMPOTENCY_KEY(3),
        /** Opaque authorization value. */
        AUTHORIZATION(4),
        /** Generated required scopes. */
        REQUIRED_SCOPES(5),
        /** Negotiated compression codec. */
        COMPRESSION_CODEC(6),
        /** Uncompressed payload length. */
        ORIGINAL_LENGTH(7),
        /** Stable logical call identifier. */
        LOGICAL_CALL_ID(8),
        /** Retry attempt number. */
        RETRY_ATTEMPT(9),
        /** Event resume cursor. */
        EVENT_CURSOR(10),
        /** Event sequence number. */
        EVENT_SEQUENCE(11),
        /** Application content type. */
        CONTENT_TYPE(12),
        /** Anonymous diagnostic correlation identifier. */
        DIAGNOSTIC_CORRELATION(13);

        private final int wireId;

        ReservedKey(int wireId) {
            this.wireId = wireId;
        }

        private static ReservedKey fromWire(int value) {
            for (ReservedKey key : values()) if (key.wireId == value) return key;
            return null;
        }
    }

    /**
     * Local decode limits for one metadata block.
     *
     * @param maxBytes maximum encoded block bytes, excluding the length prefix
     * @param maxEntries maximum number of entries
     * @param maxValueBytes maximum bytes in one value
     */
    public record Limits(int maxBytes, int maxEntries, int maxValueBytes) {
        /** Validates that every local limit is positive. */
        public Limits {
            if (maxBytes < 1 || maxEntries < 1 || maxValueBytes < 1) {
                throw new IllegalArgumentException("metadata limits must be positive");
            }
        }

        /**
         * Returns conservative default limits.
         *
         * @return 16 KiB, 64 entries, and 8 KiB per value
         */
        public static Limits defaults() {
            return new Limits(16 * 1024, 64, 8 * 1024);
        }
    }

    /** Immutable result of decoding a metadata prefix. */
    public static final class Decoded {
        private final MoonLightMetadata metadata;
        private final byte[] payload;

        private Decoded(MoonLightMetadata metadata, byte[] payload) {
            this.metadata = metadata;
            this.payload = payload.clone();
        }

        /**
         * Returns the decoded metadata.
         *
         * @return decoded immutable metadata
         */
        public MoonLightMetadata metadata() {
            return metadata;
        }

        /**
         * Returns a copy of the application payload.
         *
         * @return defensive copy of the application payload
         */
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record WireKey(ReservedKey reserved, String name) implements Comparable<WireKey> {
        private static WireKey reserved(ReservedKey key) {
            return new WireKey(key, "");
        }

        private static WireKey user(String name) {
            return new WireKey(null, name);
        }

        @Override
        public int compareTo(WireKey other) {
            if (reserved != null && other.reserved == null) return -1;
            if (reserved == null && other.reserved != null) return 1;
            if (reserved != null) return Integer.compare(reserved.wireId, other.reserved.wireId);
            return name.compareTo(other.name);
        }
    }
}
