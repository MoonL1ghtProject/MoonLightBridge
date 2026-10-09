package ru.moonlightproject.bridge.client;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** One direct, multiplexed MoonLightBridge connection without reconnect supervision. */
public final class MoonLightClient implements MoonLightChannel {
    private static final System.Logger LOGGER = System.getLogger("ru.moonlightproject.bridge.client");
    private static final int MAGIC = 0x4D4C4252;
    private static final int VERSION = 2;
    private static final int REQUEST = 1;
    private static final int RESPONSE = 2;
    private static final int ERROR = 3;
    private static final int HELLO = 16;
    private static final int WELCOME = 17;
    private static final int CANCEL = 18;
    private static final int PING = 19;
    private static final int PONG = 20;
    private static final int GOODBYE = 21;
    private static final int HEALTH = 22;
    private static final int HEALTH_STATUS = 23;
    private static final int EVENT = 24;
    private static final int STREAM_ITEM = 25;
    private static final int STREAM_END = 26;
    private static final int STREAM_CREDIT = 27;
    private static final int GOAWAY = 28;
    private static final int FLAG_HAS_METADATA = 1;
    private static final int DEFAULT_MAX_BODY_LENGTH = 8 * 1024 * 1024;
    private static final int DEFAULT_MAX_DECODED_BODY_LENGTH = 16 * 1024 * 1024;
    private static final int DEFAULT_MAX_METADATA_LENGTH = 16 * 1024;
    private static final int DEFAULT_MAX_IN_FLIGHT = 256;
    private static final int DEFAULT_MAX_CONCURRENT_STREAMS = 64;
    private static final int DEFAULT_INITIAL_STREAM_CREDIT = 32;
    private static final long FEATURE_TRACE_CONTEXT = 1L << 3;
    private static final long FEATURE_SERVER_EVENTS = 1L << 4;
    private static final long FEATURE_HEALTH = 1L << 5;
    private static final long FEATURE_SERVER_STREAMING = 1L << 6;
    private static final long FEATURES = 1 | (1L << 1) | (1L << 2) | FEATURE_TRACE_CONTEXT
        | FEATURE_SERVER_EVENTS | FEATURE_HEALTH | FEATURE_SERVER_STREAMING;

    private final Closeable connection;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final ConcurrentMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, StreamRequest> streams = new ConcurrentHashMap<>();
    private final Semaphore inFlight;
    private final int maxBodyLength;
    private final int maxDecodedBodyLength;
    private final int maxMetadataLength;
    private final int negotiatedCompressionCodecs;
    private final MoonLightCompression.Options compressionOptions;
    private final MoonLightCompression.DecodedByteBudget decodedByteBudget;
    private final long negotiatedFeatures;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<MoonLightDrainHandle> drainHandle = new AtomicReference<>();
    private final CompletableFuture<Throwable> termination = new CompletableFuture<>();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final ConcurrentMap<Integer, CopyOnWriteArrayList<Consumer<byte[]>>> eventListeners =
        new ConcurrentHashMap<>();
    private final MoonLightPerformanceOptions performance;
    private final ArrayBlockingQueue<OutboundFrame> outgoing;
    private final ByteArrayPool bufferPool;
    private final Semaphore outgoingRequestBytes = new Semaphore(32 * 1024 * 1024);
    private final Thread writerThread;
    private final MoonLightTelemetry telemetry;
    private final List<MoonLightInterceptor> interceptors;
    private final ThreadPoolExecutor callbackExecutor = new ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1_024),
        Thread.ofPlatform().daemon().name("moonlight-bridge-callback-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private static final ScheduledThreadPoolExecutor STREAM_TIMEOUTS = streamTimeouts();

    private static ScheduledThreadPoolExecutor streamTimeouts() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("moonlight-bridge-stream-timeout").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * Opens a plaintext TCP connection with automatic defaults.
     *
     * @param host backend host name or address
     * @param port backend TCP port
     * @throws IOException when the connection or protocol handshake fails
     */
    public MoonLightClient(String host, int port) throws IOException {
        this(openTcp(host, port), MoonLightPerformanceOptions.automatic("tcp"), MoonLightTelemetry.automatic(), List.of());
    }

    /**
     * Opens a plaintext TCP connection with automatic defaults.
     *
     * @param host backend host name or address
     * @param port backend TCP port
     * @return connected direct client
     * @throws IOException when the connection or protocol handshake fails
     */
    public static MoonLightClient tcp(String host, int port) throws IOException {
        return new MoonLightClient(openTcp(host, port), MoonLightPerformanceOptions.automatic("tcp"), MoonLightTelemetry.automatic(), List.of());
    }

    /**
     * Opens a {@code tcp://}, {@code tls://}, or {@code unix:} endpoint.
     *
     * @param endpoint complete transport endpoint
     * @return connected direct client
     * @throws IOException when the endpoint is invalid or the connection fails
     */
    public static MoonLightClient connect(String endpoint) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }

        return connect(uri, MoonLightPerformanceOptions.automatic(uri.getScheme()), MoonLightTelemetry.automatic(), List.of());
    }

    /**
     * Opens an endpoint using explicit writer and pooling settings.
     *
     * @param endpoint complete transport endpoint
     * @param performance writer, batching, and buffer settings
     * @return connected direct client
     * @throws IOException when the endpoint is invalid or the connection fails
     */
    public static MoonLightClient connect(String endpoint, MoonLightPerformanceOptions performance) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }
        return connect(uri, performance, MoonLightTelemetry.automatic(), List.of());
    }

    /**
     * Opens an endpoint using explicit performance and instrumentation implementations.
     *
     * @param endpoint complete transport endpoint
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @return connected direct client
     * @throws IOException when the endpoint is invalid or the connection fails
     */
    public static MoonLightClient connect(
        String endpoint, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry
    ) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }
        return connect(uri, performance, telemetry, List.of());
    }

    /**
     * Opens an endpoint with an immutable interceptor chain.
     *
     * @param endpoint complete transport endpoint
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @param interceptors call interceptors in registration order
     * @return connected direct client
     * @throws IOException when the endpoint is invalid or the connection fails
     */
    public static MoonLightClient connect(
        String endpoint,
        MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }
        return connect(uri, performance, telemetry, interceptors);
    }

    private static MoonLightClient connect(
        URI uri, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        return switch (uri.getScheme()) {
            case "tcp" -> {
                if (uri.getHost() == null || uri.getPort() < 1) {
                    throw new IOException("TCP endpoint must be tcp://host:port");
                }
                yield new MoonLightClient(openTcp(uri.getHost(), uri.getPort()), performance, telemetry, interceptors);
            }
            case "tls" -> {
                if (uri.getHost() == null || uri.getPort() < 1) {
                    throw new IOException("TLS endpoint must be tls://host:port");
                }
                try { yield openTls(uri.getHost(), uri.getPort(), SSLContext.getDefault(), performance, telemetry, interceptors); }
                catch (java.security.NoSuchAlgorithmException error) {
                    throw new IOException("default TLS context is unavailable", error);
                }
            }
            case "unix" -> {
                if (uri.getPath() == null || uri.getPath().isBlank()) {
                    throw new IOException("Unix endpoint must contain an absolute socket path");
                }
                yield unix(Path.of(uri.getPath()), performance, telemetry, interceptors);
            }
            default -> throw new IOException("unsupported MoonLightBridge transport: " + uri.getScheme());
        };
    }

    /**
     * Opens a Unix-domain socket using automatic local-transport defaults.
     *
     * @param path filesystem path of the listening socket
     * @return connected direct client
     * @throws IOException when the socket or protocol handshake fails
     */
    public static MoonLightClient unix(Path path) throws IOException {
        return unix(path, MoonLightPerformanceOptions.automatic("unix"), MoonLightTelemetry.automatic());
    }

    /**
     * Opens a Unix-domain socket using explicit performance settings.
     *
     * @param path filesystem path of the listening socket
     * @param performance writer, batching, and buffer settings
     * @return connected direct client
     * @throws IOException when the socket or protocol handshake fails
     */
    public static MoonLightClient unix(Path path, MoonLightPerformanceOptions performance) throws IOException {
        return unix(path, performance, MoonLightTelemetry.automatic());
    }

    /**
     * Opens a Unix-domain socket using explicit performance and instrumentation settings.
     *
     * @param path filesystem path of the listening socket
     * @param performance writer, batching, and buffer settings
     * @param telemetry local instrumentation implementation
     * @return connected direct client
     * @throws IOException when the socket or protocol handshake fails
     */
    public static MoonLightClient unix(
        Path path, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry
    ) throws IOException {
        return unix(path, performance, telemetry, List.of());
    }

    private static MoonLightClient unix(
        Path path, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(path));
            return new MoonLightClient(new Connection(
                channel,
                Channels.newInputStream(channel),
                Channels.newOutputStream(channel),
                () -> { }
            ), performance, telemetry, interceptors);
        } catch (IOException | RuntimeException | Error error) {
            try { channel.close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
    }

    /**
     * Opens a TLS connection using certificates and keys supplied by {@code context}.
     *
     * @param host backend host matching the peer certificate
     * @param port backend TCP port
     * @param context configured TLS or mutual-TLS context
     * @return connected direct client
     * @throws IOException when TLS or the protocol handshake fails
     */
    public static MoonLightClient tls(String host, int port, SSLContext context) throws IOException {
        return openTls(host, port, context, MoonLightPerformanceOptions.automatic("tls"), MoonLightTelemetry.automatic(), List.of());
    }

    /**
     * Opens a TLS connection using explicit performance settings.
     *
     * @param host backend host matching the peer certificate
     * @param port backend TCP port
     * @param context configured TLS or mutual-TLS context
     * @param performance writer, batching, and buffer settings
     * @return connected direct client
     * @throws IOException when TLS or the protocol handshake fails
     */
    public static MoonLightClient tls(
        String host, int port, SSLContext context, MoonLightPerformanceOptions performance
    ) throws IOException {
        return openTls(host, port, context, performance, MoonLightTelemetry.automatic(), List.of());
    }

    private static MoonLightClient openTls(
        String host, int port, SSLContext context, MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry, List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5_000);
            socket.setUseClientMode(true);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return new MoonLightClient(new Connection(
                socket,
                socket.getInputStream(),
                socket.getOutputStream(),
                () -> socket.setSoTimeout(0)
            ), performance, telemetry, interceptors);
        } catch (IOException | RuntimeException | Error error) {
            try { socket.close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
    }

    private MoonLightClient(
        Connection transport, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry,
        List<? extends MoonLightInterceptor> interceptors
    ) throws IOException {
        connection = transport.endpoint;
        this.performance = performance;
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.interceptors = List.copyOf(interceptors);
        decodedByteBudget = new MoonLightCompression.DecodedByteBudget(64 * 1024 * 1024);
        outgoing = new ArrayBlockingQueue<>(performance.outgoingQueueCapacity());
        bufferPool = new ByteArrayPool(performance.bufferPooling());
        input = new DataInputStream(new BufferedInputStream(transport.input));
        output = new DataOutputStream(new BufferedOutputStream(transport.output));

        try {
            byte[] hello = ByteBuffer.allocate(44)
                .putInt(DEFAULT_MAX_BODY_LENGTH)
                .putInt(DEFAULT_MAX_DECODED_BODY_LENGTH)
                .putInt(DEFAULT_MAX_METADATA_LENGTH)
                .putInt(DEFAULT_MAX_IN_FLIGHT)
                .putInt(DEFAULT_MAX_CONCURRENT_STREAMS)
                .putInt(DEFAULT_INITIAL_STREAM_CREDIT)
                .putInt(3)
                .putLong(FEATURES)
                .putLong(0)
                .array();
            writeFrameDirect(HELLO, 0, 0, 0, hello);
            Frame welcome = readFrame(44);
            if (welcome.kind != WELCOME || welcome.requestId != 0 || welcome.methodId != 0 || welcome.flags != 0 || welcome.body.length != 44) {
                throw new IOException("server did not complete the MoonLightBridge handshake");
            }
            ByteBuffer settings = ByteBuffer.wrap(welcome.body);
            maxBodyLength = settings.getInt();
            maxDecodedBodyLength = settings.getInt();
            maxMetadataLength = settings.getInt();
            int maxInFlight = settings.getInt();
            int maxConcurrentStreams = settings.getInt();
            int initialStreamCredit = settings.getInt();
            int compressionCodecs = settings.getInt();
            negotiatedCompressionCodecs = compressionCodecs;
            negotiatedFeatures = settings.getLong();
            long diagnosticFeatures = settings.getLong();
            if (maxBodyLength <= 0 || maxBodyLength > DEFAULT_MAX_BODY_LENGTH
                || maxDecodedBodyLength <= 0 || maxDecodedBodyLength > DEFAULT_MAX_DECODED_BODY_LENGTH
                || maxMetadataLength <= 0 || maxMetadataLength > DEFAULT_MAX_METADATA_LENGTH
                || maxInFlight <= 0 || maxInFlight > DEFAULT_MAX_IN_FLIGHT
                || maxConcurrentStreams <= 0 || maxConcurrentStreams > DEFAULT_MAX_CONCURRENT_STREAMS
                || initialStreamCredit <= 0 || initialStreamCredit > DEFAULT_INITIAL_STREAM_CREDIT
                || (compressionCodecs & 1) == 0 || (compressionCodecs & ~3) != 0
                || (negotiatedFeatures & ~FEATURES) != 0 || diagnosticFeatures != 0) {
                throw new IOException("server returned invalid MoonLightBridge settings");
            }
            MoonLightCompression.Options defaults = MoonLightCompression.Options.defaults();
            compressionOptions = new MoonLightCompression.Options(
                defaults.minPayloadBytes(), defaults.minSavingsBytes(), maxDecodedBodyLength,
                defaults.maxExpansionRatio(), defaults.compressionLevel());
            inFlight = new Semaphore(maxInFlight);
            transport.handshakeCompletion.complete();
        } catch (IOException | RuntimeException | Error error) {
            try { connection.close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
        writerThread = Thread.ofVirtual().name("moonlight-bridge-request-writer").start(this::writeLoop);
        Thread.ofVirtual().name("moonlight-bridge-response-reader").start(this::readResponses);
        try { telemetry.connectionOpened(); } catch (RuntimeException ignored) { }
        LOGGER.log(System.Logger.Level.DEBUG, "MoonLightBridge connection established; features={0}", negotiatedFeatures);
    }

    private static Connection openTcp(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5_000);
            return new Connection(socket, socket.getInputStream(), socket.getOutputStream(), () -> socket.setSoTimeout(0));
        } catch (IOException | RuntimeException | Error error) {
            try { socket.close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
    }

    /**
     * Returns the feature-bit intersection negotiated during HELLO/WELCOME.
     *
     * @return negotiated protocol feature bits
     */
    public long negotiatedFeatures() { return negotiatedFeatures; }

    /**
     * Completes with the reason when the reader loop terminates.
     *
     * @return connection-lifetime completion stage
     */
    public CompletionStage<Throwable> termination() { return termination; }

    /**
     * Returns whether this direct connection has been closed.
     *
     * @return {@code true} after local or remote termination
     */
    public boolean isClosed() { return closed.get(); }
    /**
     * Returns the number of requests awaiting responses.
     *
     * @return current in-flight request count
     */
    public int pendingRequests() { return pending.size(); }
    /**
     * Returns the reader/writer failure that closed the connection, or {@code null}.
     *
     * @return terminal transport failure, or {@code null}
     */
    public Throwable lastFailure() { return lastFailure.get(); }

    public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
        return request(methodId, body, deadline, null);
    }

    @Override
    public CompletableFuture<byte[]> request(
        int methodId, byte[] body, Duration deadline, RpcPolicy policy
    ) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("MoonLightBridge client is closed"));
        if (!accepting.get()) return CompletableFuture.failedFuture(
            new RejectedExecutionException("MoonLightBridge client is draining"));
        if (policy != null) {
            if (body.length > policy.maxRequestBytes()) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("request exceeds generated RPC policy"));
            }
            deadline = policy.effectiveDeadline(deadline);
        }
        long timeoutMillis = deadline.toMillis();
        if (timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("deadline must be between 1ms and 2147483647ms"));
        }
        if (body.length > maxDecodedBodyLength) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("body is too large"));
        }
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("MoonLightBridge in-flight request limit reached"));
        }

        long requestId = allocateRequestId();
        MoonLightCallContext callContext = new MoonLightCallContext(
            methodId, requestId, deadline, policy, MoonLightMetadata.builder().build());
        int enteredInterceptors = 0;
        try {
            for (MoonLightInterceptor interceptor : interceptors) {
                enteredInterceptors++;
                MoonLightCallContext next = java.util.Objects.requireNonNull(
                    interceptor.beforeCall(callContext), "interceptor context");
                if (next.methodId() != methodId || next.requestId() != requestId) {
                    throw new IllegalArgumentException("interceptor cannot replace call identity");
                }
                callContext = next;
            }
            deadline = callContext.deadline();
            timeoutMillis = deadline.toMillis();
            if (timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("deadline must be between 1ms and 2147483647ms");
            }
        } catch (Throwable failure) {
            inFlight.release();
            notifyInterceptors(callContext, null, failure, enteredInterceptors);
            return CompletableFuture.failedFuture(failure);
        }
        MoonLightTelemetry.RequestObservation observation;
        try {
            observation = telemetry.startRequest(new MoonLightTelemetry.RequestInfo(
                methodId, requestId, body.length, policy == null ? null : policy.methodName()));
            if (observation == null) observation = MoonLightTelemetry.disabled().startRequest(null);
        } catch (RuntimeException ignored) {
            observation = MoonLightTelemetry.disabled().startRequest(null);
        }
        MoonLightTraceContext traceContext = null;
        if ((negotiatedFeatures & FEATURE_TRACE_CONTEXT) != 0) {
            try { traceContext = observation.traceContext(); }
            catch (RuntimeException ignored) { }
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(RESPONSE, methodId, true, future);
        pending.put(requestId, pendingRequest);
        try {
            enqueueRequestFrame(methodId, requestId, (int) timeoutMillis, traceContext, body,
                policy == null ? RpcPolicy.Compression.DEFAULT : policy.compression(),
                callContext.metadata());
        } catch (IOException error) {
            future.completeExceptionally(error);
        }

        future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        MoonLightTelemetry.RequestObservation completedObservation = observation;
        MoonLightCallContext completedContext = callContext;
        int completedInterceptors = enteredInterceptors;
        future.whenComplete((ignored, error) -> {
            pending.remove(requestId, pendingRequest);
            inFlight.release();
            try { completedObservation.finish(ignored == null ? 0 : ignored.length, error); }
            catch (RuntimeException ignoredTelemetryError) { }
            notifyInterceptors(completedContext, ignored, error, completedInterceptors);
            if (error != null) {
                LOGGER.log(System.Logger.Level.DEBUG,
                    "MoonLightBridge request failed; method={0}, request={1}, error={2}",
                    methodId, requestId, error.getClass().getSimpleName());
            }
            if (error instanceof TimeoutException || error instanceof CancellationException) sendCancel(requestId);
        });
        return future;
    }

    @Override
    public MoonLightServerStream<byte[]> serverStream(int methodId, byte[] body, Duration deadline) {
        return serverStream(methodId, body, deadline, null);
    }

    @Override
    public MoonLightServerStream<byte[]> serverStream(
        int methodId, byte[] body, Duration deadline, RpcPolicy policy
    ) {
        if ((negotiatedFeatures & FEATURE_SERVER_STREAMING) == 0) {
            throw new UnsupportedOperationException("server did not negotiate streaming support");
        }
        if (closed.get()) throw new CompletionException(new IOException("MoonLightBridge client is closed"));
        if (!accepting.get()) throw new RejectedExecutionException("MoonLightBridge client is draining");
        if (policy != null) {
            if (body.length > policy.maxRequestBytes()) {
                throw new IllegalArgumentException("request exceeds generated RPC policy");
            }
            deadline = policy.effectiveDeadline(deadline);
        }
        long timeoutMillis = deadline.toMillis();
        if (timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("deadline must be between 1ms and 2147483647ms");
        }
        if (body.length > maxDecodedBodyLength) throw new IllegalArgumentException("body is too large");
        if (!inFlight.tryAcquire()) {
            throw new RejectedExecutionException("MoonLightBridge in-flight request limit reached");
        }
        long requestId = allocateRequestId();
        MoonLightCallContext callContext = new MoonLightCallContext(
            methodId, requestId, deadline, policy, MoonLightMetadata.builder().build());
        int enteredInterceptors = 0;
        try {
            for (MoonLightInterceptor interceptor : interceptors) {
                enteredInterceptors++;
                MoonLightCallContext next = java.util.Objects.requireNonNull(
                    interceptor.beforeCall(callContext), "interceptor context");
                if (next.methodId() != methodId || next.requestId() != requestId) {
                    throw new IllegalArgumentException("interceptor cannot replace call identity");
                }
                callContext = next;
            }
            deadline = callContext.deadline();
            timeoutMillis = deadline.toMillis();
            if (timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("deadline must be between 1ms and 2147483647ms");
            }
        } catch (Throwable failure) {
            inFlight.release();
            notifyInterceptors(callContext, null, failure, enteredInterceptors);
            throw new CompletionException(failure);
        }
        MoonLightTelemetry.RequestObservation observation;
        try {
            observation = telemetry.startRequest(new MoonLightTelemetry.RequestInfo(
                methodId, requestId, body.length, policy == null ? null : policy.methodName()));
            if (observation == null) observation = MoonLightTelemetry.disabled().startRequest(null);
        } catch (RuntimeException ignored) {
            observation = MoonLightTelemetry.disabled().startRequest(null);
        }
        MoonLightTraceContext traceContext = null;
        if ((negotiatedFeatures & FEATURE_TRACE_CONTEXT) != 0) {
            try { traceContext = observation.traceContext(); }
            catch (RuntimeException ignored) { }
        }
        MoonLightTelemetry.RequestObservation completedObservation = observation;
        AtomicLong responseBytes = new AtomicLong();
        AtomicReference<MoonLightServerStream<byte[]>> reference = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> deadlineTask = new AtomicReference<>();
        MoonLightCallContext completedContext = callContext;
        int completedInterceptors = enteredInterceptors;
        MoonLightServerStream<byte[]> stream = new MoonLightServerStream<>(
            count -> sendStreamCredit(requestId, count, reference.get()),
            () -> sendCancel(requestId),
            error -> {
                streams.remove(requestId);
                ScheduledFuture<?> scheduled = deadlineTask.getAndSet(null);
                if (scheduled != null) scheduled.cancel(false);
                inFlight.release();
                try { completedObservation.finish(Math.toIntExact(Math.min(Integer.MAX_VALUE, responseBytes.get())), error); }
                catch (RuntimeException ignored) { }
                notifyInterceptors(completedContext, null, error, completedInterceptors);
            },
            callbackExecutor
        );
        reference.set(stream);
        streams.put(requestId, new StreamRequest(methodId, stream, responseBytes));
        try {
            enqueueRequestFrame(methodId, requestId, (int) timeoutMillis, traceContext, body,
                policy == null ? RpcPolicy.Compression.DEFAULT : policy.compression(),
                callContext.metadata());
        } catch (IOException error) {
            stream.fail(error);
            return stream;
        }
        ScheduledFuture<?> scheduled = STREAM_TIMEOUTS.schedule(() -> {
            if (streams.containsKey(requestId)) {
                sendCancel(requestId);
                stream.fail(new TimeoutException("MoonLightBridge stream deadline exceeded"));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        deadlineTask.set(scheduled);
        if (!streams.containsKey(requestId)) {
            ScheduledFuture<?> completed = deadlineTask.getAndSet(null);
            if (completed != null) completed.cancel(false);
        }
        return stream;
    }

    private void sendStreamCredit(long requestId, long count, MoonLightServerStream<byte[]> stream) {
        try {
            writeFrame(STREAM_CREDIT, 0, 0, requestId,
                ByteBuffer.allocate(Long.BYTES).putLong(count).array());
        } catch (IOException error) {
            stream.fail(error);
        }
    }

    public CompletableFuture<Void> ping(Duration timeout) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("MoonLightBridge client is closed"));
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("MoonLightBridge in-flight request limit reached"));
        }
        long requestId = allocateRequestId();
        byte[] nonce = ByteBuffer.allocate(Long.BYTES).putLong(System.nanoTime()).array();
        CompletableFuture<byte[]> response = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(PONG, 0, false, response);
        pending.put(requestId, pendingRequest);
        try {
            writeFrame(PING, 0, 0, requestId, nonce);
        } catch (IOException error) {
            response.completeExceptionally(error);
        }
        response.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        response.whenComplete((ignored, error) -> {
            pending.remove(requestId, pendingRequest);
            inFlight.release();
        });
        return response.thenAccept(body -> {
            if (!java.util.Arrays.equals(nonce, body)) throw new CompletionException(new IOException("PONG nonce mismatch"));
        });
    }

    @Override
    public CompletableFuture<MoonLightHealth> health(Duration timeout) {
        if ((negotiatedFeatures & FEATURE_HEALTH) == 0) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("server did not negotiate health support"));
        }
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("MoonLightBridge client is closed"));
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("MoonLightBridge in-flight request limit reached"));
        }
        long requestId = allocateRequestId();
        CompletableFuture<byte[]> response = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(HEALTH_STATUS, 0, false, response);
        pending.put(requestId, pendingRequest);
        try { writeFrame(HEALTH, 0, 0, requestId, new byte[0]); }
        catch (IOException error) { response.completeExceptionally(error); }
        response.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        response.whenComplete((ignored, error) -> {
            pending.remove(requestId, pendingRequest);
            inFlight.release();
        });
        return response.thenApply(MoonLightClient::decodeHealth);
    }

    @Override
    public AutoCloseable subscribe(int eventId, Consumer<byte[]> listener) {
        if (eventId == 0) throw new IllegalArgumentException("eventId must not be zero");
        if ((negotiatedFeatures & FEATURE_SERVER_EVENTS) == 0) {
            throw new UnsupportedOperationException("server did not negotiate event support");
        }
        Consumer<byte[]> checked = java.util.Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<Consumer<byte[]>> listeners =
            eventListeners.computeIfAbsent(eventId, ignored -> new CopyOnWriteArrayList<>());
        listeners.addIfAbsent(checked);
        return () -> {
            removeEventListener(eventId, checked);
        };
    }

    void removeEventListener(int eventId, Consumer<byte[]> listener) {
        CopyOnWriteArrayList<Consumer<byte[]>> listeners = eventListeners.get(eventId);
        if (listeners == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) eventListeners.remove(eventId, listeners);
    }

    private void sendCancel(long requestId) {
        if (closed.get()) return;
        try { writeFrame(CANCEL, 0, 0, requestId, new byte[0]); }
        catch (IOException error) { terminate(error); }
    }

    private void readResponses() {
        try {
            while (!closed.get()) {
                Frame received = readFrame(maxBodyLength);
                if (received.kind == GOAWAY) {
                    if (received.methodId != 0 || received.requestId != 0 || received.body.length != 0) {
                        throw new IOException("invalid MoonLightBridge GOAWAY frame");
                    }
                    drain(Duration.ofSeconds(30));
                    continue;
                }
                Frame frame = received.kind == RESPONSE || received.kind == EVENT
                    || received.kind == STREAM_ITEM ? decodeApplicationFrame(received) : received;
                if (frame.kind == EVENT) {
                    if (frame.requestId != 0 || frame.methodId == 0) {
                        throw new IOException("invalid MoonLightBridge EVENT frame");
                    }
                    for (Consumer<byte[]> listener : eventListeners.getOrDefault(
                        frame.methodId, new CopyOnWriteArrayList<>())) {
                        dispatchCallback(() -> {
                            try { listener.accept(frame.body); }
                            catch (RuntimeException error) {
                                LOGGER.log(System.Logger.Level.ERROR,
                                    "MoonLightBridge event listener failed; event=" + frame.methodId, error);
                            }
                        }, "event " + frame.methodId);
                    }
                    continue;
                }
                StreamRequest stream = streams.get(frame.requestId);
                if (stream != null) {
                    if (frame.methodId != stream.methodId
                        || (frame.kind != STREAM_ITEM && frame.kind != STREAM_END && frame.kind != ERROR)) {
                        throw new IOException("response does not match pending stream " + frame.requestId);
                    }
                    if (frame.kind == STREAM_ITEM) {
                        stream.responseBytes.addAndGet(frame.body.length);
                        stream.stream.emit(frame.body);
                    } else if (frame.kind == STREAM_END) {
                        if (frame.body.length != 0) throw new IOException("STREAM_END body must be empty");
                        stream.stream.complete();
                    } else {
                        stream.stream.fail(decodeRemoteError(frame));
                    }
                    continue;
                }
                PendingRequest request = pending.get(frame.requestId);
                if (request == null) continue;
                boolean expectedSuccess = validateResponse(frame, request);
                if (!pending.remove(frame.requestId, request)) continue;
                Throwable remoteError = expectedSuccess ? null : decodeRemoteError(frame);
                Thread.ofVirtual().name("moonlight-bridge-response-callback").start(() -> {
                    if (remoteError == null) request.future.complete(frame.body);
                    else request.future.completeExceptionally(remoteError);
                });
            }
        } catch (IOException error) {
            terminate(error);
        }
    }

    private void dispatchCallback(Runnable callback, String description) {
        try {
            callbackExecutor.execute(callback);
        } catch (RejectedExecutionException error) {
            LOGGER.log(System.Logger.Level.WARNING,
                "MoonLightBridge callback queue is full; dropped " + description);
        }
    }

    private static MoonLightRemoteException decodeRemoteError(Frame frame) throws IOException {
        if (frame.body.length < 2) throw new IOException("truncated MoonLightBridge error body");
        int code = Short.toUnsignedInt(ByteBuffer.wrap(frame.body, 0, 2).getShort());
        String message = new String(frame.body, 2, frame.body.length - 2, StandardCharsets.UTF_8);
        return new MoonLightRemoteException(frame.methodId, ErrorCode.fromWire(code), message);
    }

    private static boolean validateResponse(Frame frame, PendingRequest request) throws IOException {
        boolean expectedSuccess = frame.kind == request.expectedKind;
        boolean expectedError = frame.kind == ERROR && request.errorAllowed;
        if ((!expectedSuccess && !expectedError) || frame.methodId != request.methodId) {
            throw new IOException(
                "response does not match pending request " + frame.requestId
                    + ": expected kind=" + request.expectedKind + ", method=" + request.methodId
                    + "; received kind=" + frame.kind + ", method=" + frame.methodId
            );
        }
        return expectedSuccess;
    }

    private Frame decodeApplicationFrame(Frame frame) throws IOException {
        if ((frame.flags & FLAG_HAS_METADATA) == 0) return frame;
        MoonLightMetadata.Decoded decoded = MoonLightMetadata.decode(
            frame.body, new MoonLightMetadata.Limits(maxMetadataLength, 64, 8 * 1024));
        byte[] codecBytes = decoded.metadata().get(MoonLightMetadata.ReservedKey.COMPRESSION_CODEC);
        byte[] lengthBytes = decoded.metadata().get(MoonLightMetadata.ReservedKey.ORIGINAL_LENGTH);
        byte[] payload = decoded.payload();
        if (codecBytes == null && lengthBytes == null) {
            return new Frame(frame.kind, 0, frame.methodId, frame.requestId, payload);
        }
        if (codecBytes == null || codecBytes.length != 1
            || lengthBytes == null || lengthBytes.length != Integer.BYTES) {
            throw new IOException("incomplete compression metadata");
        }
        MoonLightCompression.Codec codec = MoonLightCompression.Codec.fromWire(
            Byte.toUnsignedInt(codecBytes[0]));
        if (codec != MoonLightCompression.Codec.ZSTD
            || (negotiatedCompressionCodecs & 2) == 0) {
            throw new IOException("compression codec was not negotiated");
        }
        int originalLength = ByteBuffer.wrap(lengthBytes).getInt();
        if (originalLength < 0) throw new IOException("negative original payload length");
        byte[] body = MoonLightCompression.decode(
            MoonLightCompression.transportEncoded(codec, originalLength, payload),
            compressionOptions,
            decodedByteBudget);
        return new Frame(frame.kind, 0, frame.methodId, frame.requestId, body);
    }

    private Frame readFrame(int bodyLimit) throws IOException {
        int magic = input.readInt();
        int version = input.readUnsignedByte();
        int kind = input.readUnsignedByte();
        int flags = input.readUnsignedShort();
        int bodyLength = input.readInt();
        int methodId = input.readInt();
        long requestId = input.readLong();
        if (magic != MAGIC || version != VERSION
            || (flags & ~FLAG_HAS_METADATA) != 0
            || bodyLength < 0 || bodyLength > bodyLimit) {
            throw new IOException("invalid MoonLightBridge frame header");
        }
        byte[] body = input.readNBytes(bodyLength);
        if (body.length != bodyLength) throw new EOFException("truncated MoonLightBridge frame body");
        return new Frame(kind, flags, methodId, requestId, body);
    }

    private void writeFrame(int kind, int flags, int methodId, long requestId, byte[] body) throws IOException {
        enqueueFrame(kind, flags, methodId, requestId, body, null);
    }

    private void enqueueFrame(
        int kind, int flags, int methodId, long requestId, byte[] body, CompletableFuture<Void> written
    ) throws IOException {
        int length = 24 + body.length;
        byte[] encoded = bufferPool.acquire(length);
        ByteBuffer target = ByteBuffer.wrap(encoded);
        target.putInt(MAGIC).put((byte) VERSION).put((byte) kind).putShort((short) flags)
            .putInt(body.length).putInt(methodId).putLong(requestId).put(body);
        if (!outgoing.offer(new OutboundFrame(encoded, length, written, 0, false, 0, Long.MAX_VALUE))) {
            bufferPool.release(encoded);
            throw new OutgoingQueueFullException(performance.outgoingQueueCapacity());
        }
    }

    private void enqueueRequestFrame(
        int methodId, long requestId, int timeoutMillis, MoonLightTraceContext traceContext,
        byte[] body, RpcPolicy.Compression compression, MoonLightMetadata userMetadata
    )
        throws IOException {
        MoonLightMetadata.Builder metadata = MoonLightMetadata.builder()
            .putReserved(MoonLightMetadata.ReservedKey.DEADLINE_MILLIS,
                ByteBuffer.allocate(Integer.BYTES).putInt(timeoutMillis).array());
        userMetadata.copyInto(metadata);
        if (traceContext != null) {
            ByteBuffer trace = ByteBuffer.allocate(MoonLightTraceContext.WIRE_LENGTH);
            traceContext.writeTo(trace);
            metadata.putReserved(MoonLightMetadata.ReservedKey.TRACE_CONTEXT, trace.array());
        }
        boolean zstdNegotiated = (negotiatedCompressionCodecs & 2) != 0;
        if (compression == RpcPolicy.Compression.REQUIRED && !zstdNegotiated) {
            throw new IOException("generated RPC policy requires zstd compression");
        }
        MoonLightCompression.Encoded compressed = zstdNegotiated
            && compression != RpcPolicy.Compression.DISABLED
            ? MoonLightCompression.encode(body, MoonLightCompression.Codec.ZSTD, compressionOptions)
            : null;
        byte[] encodedBody = compressed == null ? body : compressed.internalBytes();
        if (compressed != null && compressed.codec() == MoonLightCompression.Codec.ZSTD) {
            metadata.putReserved(
                MoonLightMetadata.ReservedKey.COMPRESSION_CODEC,
                new byte[] {(byte) compressed.codec().wireValue()});
            metadata.putReserved(
                MoonLightMetadata.ReservedKey.ORIGINAL_LENGTH,
                ByteBuffer.allocate(Integer.BYTES).putInt(compressed.originalLength()).array());
        }
        byte[] metadataPrefix = metadata.build().encodePrefix();
        if (metadataPrefix.length - Integer.BYTES > maxMetadataLength) {
            throw new IOException("request metadata exceeds negotiated limit");
        }
        int bodyLength = Math.addExact(encodedBody.length, metadataPrefix.length);
        if (bodyLength > maxBodyLength) throw new IOException("request body exceeds negotiated limit");
        int length = 24 + bodyLength;
        if (!outgoingRequestBytes.tryAcquire(length)) {
            throw new OutgoingQueueFullException(performance.outgoingQueueCapacity());
        }
        byte[] encoded = bufferPool.acquire(length);
        ByteBuffer target = ByteBuffer.wrap(encoded);
        target.putInt(MAGIC).put((byte) VERSION).put((byte) REQUEST)
            .putShort((short) FLAG_HAS_METADATA).putInt(bodyLength).putInt(methodId)
            .putLong(requestId).put(metadataPrefix);
        target.put(encodedBody);
        long expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (!outgoing.offer(new OutboundFrame(
            encoded, length, null, length, true, requestId, expiresAt))) {
            bufferPool.release(encoded);
            outgoingRequestBytes.release(length);
            throw new OutgoingQueueFullException(performance.outgoingQueueCapacity());
        }
    }

    private void notifyInterceptors(
        MoonLightCallContext context,
        byte[] response,
        Throwable failure,
        int entered
    ) {
        for (int index = entered - 1; index >= 0; index--) {
            try {
                interceptors.get(index).afterCall(context, response, failure);
            } catch (Throwable ignored) { }
        }
    }

    private void writeFrameDirect(int kind, int flags, int methodId, long requestId, byte[] body) throws IOException {
        synchronized (output) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(kind);
            output.writeShort(flags);
            output.writeInt(body.length);
            output.writeInt(methodId);
            output.writeLong(requestId);
            output.write(body);
            output.flush();
        }
    }

    private void writeLoop() {
        List<OutboundFrame> batch = new ArrayList<>(performance.maxBatchFrames());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                OutboundFrame first;
                do {
                    first = outgoing.take();
                    if (isExpired(first)) releaseOutbound(first, null);
                    else break;
                } while (true);
                batch.add(first);
                int bytes = first.length;
                while (batch.size() < performance.maxBatchFrames()) {
                    OutboundFrame next = outgoing.peek();
                    if (next == null || bytes + next.length > performance.maxBatchBytes()) break;
                    next = outgoing.poll();
                    if (next == null) break;
                    if (isExpired(next)) {
                        releaseOutbound(next, null);
                        continue;
                    }
                    batch.add(next);
                    bytes += next.length;
                }

                if (batch.size() == 1) {
                    output.write(first.bytes, 0, first.length);
                } else {
                    byte[] combined = bufferPool.acquire(bytes);
                    try {
                        int offset = 0;
                        for (OutboundFrame frame : batch) {
                            System.arraycopy(frame.bytes, 0, combined, offset, frame.length);
                            offset += frame.length;
                        }
                        output.write(combined, 0, bytes);
                    } finally {
                        bufferPool.release(combined);
                    }
                }
                output.flush();
                for (OutboundFrame frame : batch) {
                    releaseOutbound(frame, null);
                }
                batch.clear();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            for (OutboundFrame frame : batch) {
                releaseOutbound(frame, error);
            }
            terminate(error);
        } finally {
            OutboundFrame frame;
            while ((frame = outgoing.poll()) != null) {
                releaseOutbound(frame, new IOException("MoonLightBridge writer stopped"));
            }
        }
    }

    private boolean isExpired(OutboundFrame frame) {
        return frame.requestFrame && (System.nanoTime() >= frame.expiresAtNanos
            || (!pending.containsKey(frame.requestId) && !streams.containsKey(frame.requestId)));
    }

    private void releaseOutbound(OutboundFrame frame, IOException error) {
        bufferPool.release(frame.bytes);
        if (frame.reservedBytes > 0) outgoingRequestBytes.release(frame.reservedBytes);
        if (frame.written != null) {
            if (error == null) frame.written.complete(null);
            else frame.written.completeExceptionally(error);
        }
    }

    private void failAll(Throwable error) {
        pending.forEach((id, request) -> request.future.completeExceptionally(error));
        pending.clear();
        streams.forEach((id, request) -> request.stream.fail(error));
        streams.clear();
    }

    private static MoonLightHealth decodeHealth(byte[] body) {
        if (body.length != 32) throw new CompletionException(new IOException("invalid HEALTH_STATUS body"));
        ByteBuffer data = ByteBuffer.wrap(body);
        int protocolVersion = Byte.toUnsignedInt(data.get());
        boolean ready = data.get() != 0;
        data.getShort();
        long activeConnections = data.getLong();
        long activeRequests = data.getLong();
        long maxInFlight = Integer.toUnsignedLong(data.getInt());
        long uptimeMillis = data.getLong();
        return new MoonLightHealth(protocolVersion, ready, activeConnections, activeRequests,
            maxInFlight, Duration.ofMillis(uptimeMillis));
    }

    private long allocateRequestId() {
        long candidate;
        do {
            candidate = nextRequestId.getAndIncrement();
        } while (candidate == 0 || pending.containsKey(candidate) || streams.containsKey(candidate));
        return candidate;
    }

    @Override
    public MoonLightDrainHandle drain(Duration timeout) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("drain timeout must be positive");
        }
        MoonLightDrainHandle existing = drainHandle.get();
        if (existing != null) return existing;
        MoonLightDrainHandle created = new MoonLightDrainHandle();
        if (!drainHandle.compareAndSet(null, created)) return drainHandle.get();
        accepting.set(false);
        Thread.ofVirtual().name("moonlight-bridge-drain").start(() -> {
            long deadline;
            try {
                deadline = Math.addExact(System.nanoTime(), timeout.toNanos());
            } catch (ArithmeticException overflow) {
                deadline = Long.MAX_VALUE;
            }
            while ((!pending.isEmpty() || !streams.isEmpty()) && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            if (!pending.isEmpty() || !streams.isEmpty()) {
                TimeoutException failure = new TimeoutException("MoonLightBridge client drain timed out");
                created.fail(failure);
                try { close(); } catch (IOException ignored) { }
                return;
            }
            try {
                close();
                created.complete();
            } catch (IOException failure) {
                created.fail(failure);
            }
        });
        return created;
    }

    @Override
    public void close() throws IOException {
        accepting.set(false);
        if (!closed.compareAndSet(false, true)) return;
        IOException reason = new IOException("MoonLightBridge client closed");
        CompletableFuture<Void> goodbye = new CompletableFuture<>();
        try {
            enqueueFrame(GOODBYE, 0, 0, 0, new byte[0], goodbye);
            try { goodbye.get(250, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | TimeoutException ignored) { }
        } finally {
            connection.close();
            writerThread.interrupt();
            callbackExecutor.shutdownNow();
            failAll(reason);
            termination.complete(reason);
            try { telemetry.connectionClosed(reason); } catch (RuntimeException ignored) { }
            LOGGER.log(System.Logger.Level.DEBUG, "MoonLightBridge connection closed by client");
        }
    }

    private void terminate(IOException reason) {
        if (!closed.compareAndSet(false, true)) return;
        try { connection.close(); } catch (IOException suppressed) { reason.addSuppressed(suppressed); }
        writerThread.interrupt();
        callbackExecutor.shutdownNow();
        lastFailure.set(reason);
        failAll(reason);
        termination.complete(reason);
        try { telemetry.connectionClosed(reason); } catch (RuntimeException ignored) { }
        LOGGER.log(System.Logger.Level.WARNING, "MoonLightBridge connection terminated", reason);
    }

    @FunctionalInterface
    private interface HandshakeCompletion { void complete() throws IOException; }

    private record Connection(
        Closeable endpoint,
        InputStream input,
        OutputStream output,
        HandshakeCompletion handshakeCompletion
    ) { }

    private record Frame(int kind, int flags, int methodId, long requestId, byte[] body) { }

    private record PendingRequest(
        int expectedKind,
        int methodId,
        boolean errorAllowed,
        CompletableFuture<byte[]> future
    ) { }

    private record StreamRequest(
        int methodId,
        MoonLightServerStream<byte[]> stream,
        AtomicLong responseBytes
    ) { }

    private record OutboundFrame(
        byte[] bytes,
        int length,
        CompletableFuture<Void> written,
        int reservedBytes,
        boolean requestFrame,
        long requestId,
        long expiresAtNanos
    ) { }

    /** Backpressure failure raised when the bounded writer queue cannot accept a frame. */
    public static final class OutgoingQueueFullException extends IOException {
        /**
         * Creates a failure containing the configured queue capacity.
         *
         * @param capacity configured bounded queue capacity
         */
        public OutgoingQueueFullException(int capacity) {
            super("MoonLightBridge outgoing queue is full (capacity=" + capacity + ")");
        }
    }

    /** Transport-level error classification decoded from an ERROR response. */
    public enum ErrorCode {
        /** Requested method is not registered. */
        UNKNOWN_METHOD(1),
        /** Payload or request metadata is invalid. */
        INVALID_REQUEST(2),
        /** Backend deadline expired. */
        DEADLINE_EXCEEDED(3),
        /** Request was cancelled. */
        CANCELLED(4),
        /** Backend concurrency or resource limit was reached. */
        RESOURCE_EXHAUSTED(5),
        /** Backend handler failed unexpectedly. */
        INTERNAL(6),
        /** Authentication credentials are absent or invalid. */
        UNAUTHENTICATED(7),
        /** Authenticated caller lacks a required permission. */
        PERMISSION_DENIED(8),
        /** Backend is unavailable or draining. */
        UNAVAILABLE(9),
        /** Compression validation failed. */
        COMPRESSION_FAILURE(10),
        /** Requested event history is no longer available. */
        REPLAY_GAP(11),
        /** Operation cannot run in the current state. */
        FAILED_PRECONDITION(12),
        /** Peer protocol version or capability is unsupported. */
        UNSUPPORTED_PROTOCOL(13),
        /** Received an error code newer than this client understands. */
        UNKNOWN(-1);

        private final int wireValue;
        ErrorCode(int wireValue) { this.wireValue = wireValue; }
        /**
         * Returns the unsigned protocol value, or {@code -1} for {@link #UNKNOWN}.
         *
         * @return wire-level error code
         */
        public int wireValue() { return wireValue; }
        static ErrorCode fromWire(int value) {
            for (ErrorCode code : values()) if (code.wireValue == value) return code;
            return UNKNOWN;
        }
    }

    /** Structured remote handler failure retaining method and transport error code. */
    public static final class MoonLightRemoteException extends RuntimeException {
        /** Generated method identifier associated with the remote error. */
        private final int methodId;
        /** Structured wire error classification. */
        private final ErrorCode code;

        /**
         * Creates a decoded remote failure.
         *
         * @param methodId method whose handler failed
         * @param code structured protocol error classification
         * @param message backend-provided safe error message
         */
        public MoonLightRemoteException(int methodId, ErrorCode code, String message) {
            super(message);
            this.methodId = methodId;
            this.code = code;
        }

        /**
         * Returns the generated method ID that failed.
         *
         * @return failed method identifier
         */
        public int methodId() { return methodId; }
        /**
         * Returns the decoded transport error code.
         *
         * @return structured protocol error code
         */
        public ErrorCode code() { return code; }
    }
}
