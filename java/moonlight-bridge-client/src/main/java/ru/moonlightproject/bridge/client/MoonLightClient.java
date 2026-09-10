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

public final class MoonLightClient implements MoonLightChannel {
    private static final System.Logger LOGGER = System.getLogger("ru.moonlightproject.bridge.client");
    private static final int MAGIC = 0x4D4C4252;
    private static final int VERSION = 1;
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
    private static final int FLAG_HAS_DEADLINE = 1;
    private static final int FLAG_HAS_TRACE_CONTEXT = 1 << 1;
    private static final int DEFAULT_MAX_BODY_LENGTH = 8 * 1024 * 1024;
    private static final int DEFAULT_MAX_IN_FLIGHT = 256;
    private static final long FEATURE_TRACE_CONTEXT = 1L << 3;
    private static final long FEATURE_SERVER_EVENTS = 1L << 4;
    private static final long FEATURE_HEALTH = 1L << 5;
    private static final long FEATURES = 1 | (1L << 1) | (1L << 2) | FEATURE_TRACE_CONTEXT
        | FEATURE_SERVER_EVENTS | FEATURE_HEALTH;

    private final Closeable connection;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final ConcurrentMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Semaphore inFlight;
    private final int maxBodyLength;
    private final long negotiatedFeatures;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Throwable> termination = new CompletableFuture<>();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final ConcurrentMap<Integer, CopyOnWriteArrayList<Consumer<byte[]>>> eventListeners =
        new ConcurrentHashMap<>();
    private final MoonLightPerformanceOptions performance;
    private final ArrayBlockingQueue<OutboundFrame> outgoing;
    private final ByteArrayPool bufferPool;
    private final Thread writerThread;
    private final MoonLightTelemetry telemetry;

    public MoonLightClient(String host, int port) throws IOException {
        this(openTcp(host, port), MoonLightPerformanceOptions.automatic("tcp"), MoonLightTelemetry.automatic());
    }

    public static MoonLightClient tcp(String host, int port) throws IOException {
        return new MoonLightClient(openTcp(host, port), MoonLightPerformanceOptions.automatic("tcp"), MoonLightTelemetry.automatic());
    }

    public static MoonLightClient connect(String endpoint) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }

        return connect(uri, MoonLightPerformanceOptions.automatic(uri.getScheme()), MoonLightTelemetry.automatic());
    }

    public static MoonLightClient connect(String endpoint, MoonLightPerformanceOptions performance) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }
        return connect(uri, performance, MoonLightTelemetry.automatic());
    }

    public static MoonLightClient connect(
        String endpoint, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry
    ) throws IOException {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch (IllegalArgumentException error) { throw new IOException("invalid MoonLightBridge endpoint: " + endpoint, error); }
        return connect(uri, performance, telemetry);
    }

    private static MoonLightClient connect(
        URI uri, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry
    ) throws IOException {
        return switch (uri.getScheme()) {
            case "tcp" -> {
                if (uri.getHost() == null || uri.getPort() < 1) {
                    throw new IOException("TCP endpoint must be tcp://host:port");
                }
                yield new MoonLightClient(openTcp(uri.getHost(), uri.getPort()), performance, telemetry);
            }
            case "tls" -> {
                if (uri.getHost() == null || uri.getPort() < 1) {
                    throw new IOException("TLS endpoint must be tls://host:port");
                }
                try { yield openTls(uri.getHost(), uri.getPort(), SSLContext.getDefault(), performance, telemetry); }
                catch (java.security.NoSuchAlgorithmException error) {
                    throw new IOException("default TLS context is unavailable", error);
                }
            }
            case "unix" -> {
                if (uri.getPath() == null || uri.getPath().isBlank()) {
                    throw new IOException("Unix endpoint must contain an absolute socket path");
                }
                yield unix(Path.of(uri.getPath()), performance, telemetry);
            }
            default -> throw new IOException("unsupported MoonLightBridge transport: " + uri.getScheme());
        };
    }

    public static MoonLightClient unix(Path path) throws IOException {
        return unix(path, MoonLightPerformanceOptions.automatic("unix"), MoonLightTelemetry.automatic());
    }

    public static MoonLightClient unix(Path path, MoonLightPerformanceOptions performance) throws IOException {
        return unix(path, performance, MoonLightTelemetry.automatic());
    }

    public static MoonLightClient unix(
        Path path, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry
    ) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(path));
            return new MoonLightClient(new Connection(
                channel,
                Channels.newInputStream(channel),
                Channels.newOutputStream(channel),
                () -> { }
            ), performance, telemetry);
        } catch (IOException | RuntimeException | Error error) {
            try { channel.close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
    }

    public static MoonLightClient tls(String host, int port, SSLContext context) throws IOException {
        return openTls(host, port, context, MoonLightPerformanceOptions.automatic("tls"), MoonLightTelemetry.automatic());
    }

    public static MoonLightClient tls(
        String host, int port, SSLContext context, MoonLightPerformanceOptions performance
    ) throws IOException {
        return openTls(host, port, context, performance, MoonLightTelemetry.automatic());
    }

    private static MoonLightClient openTls(
        String host, int port, SSLContext context, MoonLightPerformanceOptions performance,
        MoonLightTelemetry telemetry
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
            ), performance, telemetry);
        } catch (IOException | RuntimeException | Error error) {
            try { socket.close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
    }

    private MoonLightClient(
        Connection transport, MoonLightPerformanceOptions performance, MoonLightTelemetry telemetry
    ) throws IOException {
        connection = transport.endpoint;
        this.performance = performance;
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        outgoing = new ArrayBlockingQueue<>(performance.outgoingQueueCapacity());
        bufferPool = new ByteArrayPool(performance.bufferPooling());
        input = new DataInputStream(new BufferedInputStream(transport.input));
        output = new DataOutputStream(new BufferedOutputStream(transport.output));

        try {
            byte[] hello = ByteBuffer.allocate(16)
                .putInt(DEFAULT_MAX_BODY_LENGTH)
                .putInt(DEFAULT_MAX_IN_FLIGHT)
                .putLong(FEATURES)
                .array();
            writeFrameDirect(HELLO, 0, 0, 0, hello);
            Frame welcome = readFrame(16);
            if (welcome.kind != WELCOME || welcome.requestId != 0 || welcome.body.length != 16) {
                throw new IOException("server did not complete the MoonLightBridge handshake");
            }
            ByteBuffer settings = ByteBuffer.wrap(welcome.body);
            maxBodyLength = settings.getInt();
            int maxInFlight = settings.getInt();
            negotiatedFeatures = settings.getLong();
            if (maxBodyLength <= 0 || maxInFlight <= 0) throw new IOException("server returned invalid MoonLightBridge settings");
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

    public long negotiatedFeatures() { return negotiatedFeatures; }

    public CompletionStage<Throwable> termination() { return termination; }

    public boolean isClosed() { return closed.get(); }
    public int pendingRequests() { return pending.size(); }
    public Throwable lastFailure() { return lastFailure.get(); }

    public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("MoonLightBridge client is closed"));
        long timeoutMillis = deadline.toMillis();
        if (timeoutMillis <= 0 || timeoutMillis > Integer.MAX_VALUE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("deadline must be between 1ms and 2147483647ms"));
        }
        if (body.length > maxBodyLength - Integer.BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("body is too large"));
        }
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("MoonLightBridge in-flight request limit reached"));
        }

        long requestId = allocateRequestId();
        MoonLightTelemetry.RequestObservation observation;
        try {
            observation = telemetry.startRequest(new MoonLightTelemetry.RequestInfo(methodId, requestId, body.length));
            if (observation == null) observation = MoonLightTelemetry.disabled().startRequest(null);
        } catch (RuntimeException ignored) {
            observation = MoonLightTelemetry.disabled().startRequest(null);
        }
        MoonLightTraceContext traceContext = null;
        if ((negotiatedFeatures & FEATURE_TRACE_CONTEXT) != 0) {
            try { traceContext = observation.traceContext(); }
            catch (RuntimeException ignored) { }
        }
        if (body.length > maxBodyLength - Integer.BYTES
            - (traceContext == null ? 0 : MoonLightTraceContext.WIRE_LENGTH)) {
            IllegalArgumentException error = new IllegalArgumentException("body is too large with trace metadata");
            try { observation.finish(0, error); } catch (RuntimeException ignored) { }
            inFlight.release();
            return CompletableFuture.failedFuture(error);
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(RESPONSE, methodId, true, future);
        pending.put(requestId, pendingRequest);
        try {
            enqueueRequestFrame(methodId, requestId, (int) timeoutMillis, traceContext, body);
        } catch (IOException error) {
            future.completeExceptionally(error);
        }

        future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        MoonLightTelemetry.RequestObservation completedObservation = observation;
        future.whenComplete((ignored, error) -> {
            pending.remove(requestId, pendingRequest);
            inFlight.release();
            try { completedObservation.finish(ignored == null ? 0 : ignored.length, error); }
            catch (RuntimeException ignoredTelemetryError) { }
            if (error != null) {
                LOGGER.log(System.Logger.Level.DEBUG,
                    "MoonLightBridge request failed; method={0}, request={1}, error={2}",
                    methodId, requestId, error.getClass().getSimpleName());
            }
            if (error instanceof TimeoutException || error instanceof CancellationException) sendCancel(requestId);
        });
        return future;
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
        catch (IOException ignored) { }
    }

    private void readResponses() {
        try {
            while (!closed.get()) {
                Frame frame = readFrame(maxBodyLength);
                if (frame.kind == EVENT) {
                    if (frame.requestId != 0 || frame.methodId == 0) {
                        throw new IOException("invalid MoonLightBridge EVENT frame");
                    }
                    for (Consumer<byte[]> listener : eventListeners.getOrDefault(
                        frame.methodId, new CopyOnWriteArrayList<>())) {
                        try { listener.accept(frame.body); }
                        catch (RuntimeException error) {
                            LOGGER.log(System.Logger.Level.ERROR,
                                "MoonLightBridge event listener failed; event=" + frame.methodId, error);
                        }
                    }
                    continue;
                }
                PendingRequest request = pending.get(frame.requestId);
                if (request == null) continue;
                boolean expectedSuccess = frame.kind == request.expectedKind;
                boolean expectedError = frame.kind == ERROR && request.errorAllowed;
                if ((!expectedSuccess && !expectedError) || frame.methodId != request.methodId) {
                    throw new IOException(
                        "response does not match pending request " + frame.requestId
                            + ": expected kind=" + request.expectedKind + ", method=" + request.methodId
                            + "; received kind=" + frame.kind + ", method=" + frame.methodId
                    );
                }
                if (!pending.remove(frame.requestId, request)) continue;
                if (expectedSuccess) {
                    request.future.complete(frame.body);
                } else {
                    if (frame.body.length < 2) throw new IOException("truncated MoonLightBridge error body");
                    int code = Short.toUnsignedInt(ByteBuffer.wrap(frame.body, 0, 2).getShort());
                    String message = new String(frame.body, 2, frame.body.length - 2, StandardCharsets.UTF_8);
                    request.future.completeExceptionally(
                        new MoonLightRemoteException(frame.methodId, ErrorCode.fromWire(code), message));
                }
            }
        } catch (IOException error) {
            terminate(error);
        }
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
            || (flags & ~(FLAG_HAS_DEADLINE | FLAG_HAS_TRACE_CONTEXT)) != 0
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
        if (!outgoing.offer(new OutboundFrame(encoded, length, written))) {
            bufferPool.release(encoded);
            throw new OutgoingQueueFullException(performance.outgoingQueueCapacity());
        }
    }

    private void enqueueRequestFrame(
        int methodId, long requestId, int timeoutMillis, MoonLightTraceContext traceContext, byte[] body
    )
        throws IOException {
        int flags = FLAG_HAS_DEADLINE;
        int metadataLength = Integer.BYTES;
        if (traceContext != null) {
            flags |= FLAG_HAS_TRACE_CONTEXT;
            metadataLength += MoonLightTraceContext.WIRE_LENGTH;
        }
        int bodyLength = body.length + metadataLength;
        int length = 24 + bodyLength;
        byte[] encoded = bufferPool.acquire(length);
        ByteBuffer target = ByteBuffer.wrap(encoded);
        target.putInt(MAGIC).put((byte) VERSION).put((byte) REQUEST)
            .putShort((short) flags).putInt(bodyLength).putInt(methodId)
            .putLong(requestId).putInt(timeoutMillis);
        if (traceContext != null) traceContext.writeTo(target);
        target.put(body);
        if (!outgoing.offer(new OutboundFrame(encoded, length, null))) {
            bufferPool.release(encoded);
            throw new OutgoingQueueFullException(performance.outgoingQueueCapacity());
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
                OutboundFrame first = outgoing.take();
                batch.add(first);
                int bytes = first.length;
                while (batch.size() < performance.maxBatchFrames()) {
                    OutboundFrame next = outgoing.peek();
                    if (next == null || bytes + next.length > performance.maxBatchBytes()) break;
                    next = outgoing.poll();
                    if (next == null) break;
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
                    bufferPool.release(frame.bytes);
                    if (frame.written != null) frame.written.complete(null);
                }
                batch.clear();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            for (OutboundFrame frame : batch) {
                bufferPool.release(frame.bytes);
                if (frame.written != null) frame.written.completeExceptionally(error);
            }
            terminate(error);
        } finally {
            OutboundFrame frame;
            while ((frame = outgoing.poll()) != null) {
                bufferPool.release(frame.bytes);
                if (frame.written != null) frame.written.completeExceptionally(
                    new IOException("MoonLightBridge writer stopped"));
            }
        }
    }

    private void failAll(Throwable error) {
        pending.forEach((id, request) -> request.future.completeExceptionally(error));
        pending.clear();
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
        } while (candidate == 0 || pending.containsKey(candidate));
        return candidate;
    }

    @Override
    public void close() throws IOException {
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

    private record OutboundFrame(byte[] bytes, int length, CompletableFuture<Void> written) { }

    public static final class OutgoingQueueFullException extends IOException {
        public OutgoingQueueFullException(int capacity) {
            super("MoonLightBridge outgoing queue is full (capacity=" + capacity + ")");
        }
    }

    public enum ErrorCode {
        UNKNOWN_METHOD(1), INVALID_REQUEST(2), DEADLINE_EXCEEDED(3), CANCELLED(4),
        RESOURCE_EXHAUSTED(5), INTERNAL(6), UNKNOWN(-1);

        private final int wireValue;
        ErrorCode(int wireValue) { this.wireValue = wireValue; }
        public int wireValue() { return wireValue; }
        static ErrorCode fromWire(int value) {
            for (ErrorCode code : values()) if (code.wireValue == value) return code;
            return UNKNOWN;
        }
    }

    public static final class MoonLightRemoteException extends RuntimeException {
        private final int methodId;
        private final ErrorCode code;

        public MoonLightRemoteException(int methodId, ErrorCode code, String message) {
            super(message);
            this.methodId = methodId;
            this.code = code;
        }

        public int methodId() { return methodId; }
        public ErrorCode code() { return code; }
    }
}
