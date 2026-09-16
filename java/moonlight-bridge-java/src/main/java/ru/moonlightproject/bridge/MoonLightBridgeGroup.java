package ru.moonlightproject.bridge;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.moonlightproject.bridge.client.MoonLightChannel;

/** Owns explicitly named, independent backend connections. */
public final class MoonLightBridgeGroup implements AutoCloseable {
    private final Map<String, MoonLightBridge> bridges;

    private MoonLightBridgeGroup(Map<String, MoonLightBridge> bridges) {
        this.bridges = Map.copyOf(bridges);
    }

    /**
     * Starts reconnect supervision for every named endpoint without waiting for network I/O.
     * @param endpoints backend name to endpoint mapping
     * @return group owning all started bridges
     * @throws IOException when an endpoint is invalid
     */
    public static MoonLightBridgeGroup start(Map<String, String> endpoints) throws IOException {
        return open(endpoints, false);
    }

    /**
     * Connects every named endpoint before returning and closes partial results on failure.
     * @param endpoints backend name to endpoint mapping
     * @return group owning all connected bridges
     * @throws IOException when any initial connection fails
     */
    public static MoonLightBridgeGroup connect(Map<String, String> endpoints) throws IOException {
        return open(endpoints, true);
    }

    private static MoonLightBridgeGroup open(Map<String, String> endpoints, boolean connect) throws IOException {
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty()) throw new IllegalArgumentException("at least one backend is required");
        Map<String, MoonLightBridge> opened = new LinkedHashMap<>();
        try {
            for (var entry : endpoints.entrySet()) {
                String name = Objects.requireNonNull(entry.getKey(), "backend name");
                if (name.isBlank()) throw new IllegalArgumentException("backend name must not be blank");
                String endpoint = Objects.requireNonNull(entry.getValue(), "backend endpoint");
                opened.put(name, connect ? MoonLightBridge.connect(endpoint) : MoonLightBridge.start(endpoint));
            }
            return new MoonLightBridgeGroup(opened);
        } catch (IOException | RuntimeException error) {
            closeAll(opened, error);
            throw error;
        }
    }

    /**
     * Returns the channel explicitly assigned to {@code name}.
     * @param name configured backend name
     * @return channel for that backend
     * @throws IllegalArgumentException when the name is unknown
     */
    public MoonLightChannel channel(String name) {
        return bridge(name).channel();
    }

    /**
     * Returns the lifecycle facade assigned to {@code name}.
     * @param name configured backend name
     * @return lifecycle facade for that backend
     * @throws IllegalArgumentException when the name is unknown
     */
    public MoonLightBridge bridge(String name) {
        MoonLightBridge bridge = bridges.get(name);
        if (bridge == null) throw new IllegalArgumentException("unknown MoonLightBridge backend: " + name);
        return bridge;
    }

    /**
     * Returns the immutable set of configured backend names.
     * @return configured names
     */
    public java.util.Set<String> names() {
        return bridges.keySet();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (MoonLightBridge bridge : bridges.values()) {
            try { bridge.close(); }
            catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static void closeAll(Map<String, MoonLightBridge> bridges, Throwable owner) {
        for (MoonLightBridge bridge : bridges.values()) {
            try { bridge.close(); }
            catch (IOException error) { owner.addSuppressed(error); }
        }
    }
}
