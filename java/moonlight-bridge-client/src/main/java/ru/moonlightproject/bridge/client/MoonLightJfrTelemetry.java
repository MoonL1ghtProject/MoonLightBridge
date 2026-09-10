package ru.moonlightproject.bridge.client;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Optional per-request JFR events for controlled profiling sessions. */
public final class MoonLightJfrTelemetry implements MoonLightTelemetry {
    @Override
    public RequestObservation startRequest(RequestInfo request) {
        RpcEvent event = new RpcEvent();
        if (!event.isEnabled()) return DisabledObservation.INSTANCE;
        event.methodId = Integer.toUnsignedString(request.methodId());
        event.requestId = Long.toUnsignedString(request.requestId());
        event.requestBytes = request.requestBytes();
        event.begin();
        return new RequestObservation() {
            @Override public MoonLightTraceContext traceContext() { return null; }

            @Override
            public void finish(int responseBytes, Throwable error) {
                event.responseBytes = responseBytes;
                event.success = error == null;
                event.errorType = error == null ? null : error.getClass().getName();
                event.end();
                event.commit();
            }
        };
    }

    private enum DisabledObservation implements RequestObservation {
        INSTANCE;
        @Override public MoonLightTraceContext traceContext() { return null; }
        @Override public void finish(int responseBytes, Throwable error) { }
    }

    @Name("ru.moonlightproject.bridge.RpcRequest")
    @Label("MoonLightBridge RPC request")
    @Category({"MoonLightBridge", "RPC"})
    @StackTrace(false)
    static final class RpcEvent extends Event {
        @Label("Method ID") String methodId;
        @Label("Request ID") String requestId;
        @Label("Request bytes") int requestBytes;
        @Label("Response bytes") int responseBytes;
        @Label("Succeeded") boolean success;
        @Label("Error type") String errorType;
    }
}
