package ru.moonlightproject.bridge.paper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.Plugin;

public final class PaperCallMain {
    public static void main(String[] args) {
        successfulCompletionIsDispatched();
        wrappedFailureIsUnwrapped();
        closedBridgeRejectsCallback();
        retiredTargetRejectsCallback();
        schedulerFailureIsNotReportedAsRetiredTarget();
        closeWaitsForActiveRequestsToDrain();
        System.out.println("MoonLightBridge Paper/Folia completion dispatch tests passed");
    }

    private static void successfulCompletionIsDispatched() {
        AtomicReference<String> value = new AtomicReference<>();
        var call = new PaperCall<>(CompletableFuture.completedFuture("ok"), null, () -> true);
        call.whenComplete(direct(), (result, error) -> value.set(result)).join();
        if (!"ok".equals(value.get())) throw new AssertionError("callback was not dispatched");
    }

    private static void wrappedFailureIsUnwrapped() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var source = CompletableFuture.<String>failedFuture(new CompletionException(new IOException("down")));
        var call = new PaperCall<>(source, null, () -> true);
        call.whenComplete(direct(), (result, error) -> failure.set(error)).join();
        if (!(failure.get() instanceof IOException)) throw new AssertionError("failure was not unwrapped");
    }

    private static void closedBridgeRejectsCallback() {
        AtomicBoolean active = new AtomicBoolean(false);
        var call = new PaperCall<>(CompletableFuture.completedFuture("late"), null, active::get);
        assertUnavailable(call.whenComplete(direct(), (result, error) -> { }));
    }

    private static void retiredTargetRejectsCallback() {
        var call = new PaperCall<>(CompletableFuture.completedFuture("late"), null, () -> true);
        assertUnavailable(call.whenComplete((action, unavailable) -> unavailable.run(), (result, error) -> { }));
    }

    private static void schedulerFailureIsNotReportedAsRetiredTarget() {
        var call = new PaperCall<>(CompletableFuture.completedFuture("late"), null, () -> true);
        try {
            call.whenComplete((action, unavailable) -> {
                throw new IllegalArgumentException("scheduler bug");
            }, (result, error) -> { }).join();
            throw new AssertionError("scheduler failure should have failed the callback");
        } catch (CompletionException error) {
            if (!(error.getCause() instanceof PaperCall.CallbackDispatchException)
                || !(error.getCause().getCause() instanceof IllegalArgumentException)) throw error;
        }
    }

    private static void closeWaitsForActiveRequestsToDrain() {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try (var server = new ServerSocket(0)) {
            Thread.ofPlatform().daemon().start(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    writeFrame(output, 17, 0, 0, ByteBuffer.allocate(44)
                        .putInt(8 * 1024 * 1024).putInt(16 * 1024 * 1024)
                        .putInt(16 * 1024).putInt(256).putInt(64).putInt(32)
                        .putInt(1).putLong(127).putLong(0).array());
                    Frame request = readFrame(input);
                    requestReceived.countDown();
                    releaseResponse.await();
                    writeFrame(output, 2, request.method(), request.id(), request.body());
                    readFrame(input);
                } catch (Throwable error) {
                    serverFailure.set(error);
                }
            });

            MoonLightBridge bridge = MoonLightBridge.start(
                plugin(), "tcp://127.0.0.1:" + server.getLocalPort());
            bridge.firstConnection().toCompletableFuture().get(2, TimeUnit.SECONDS);
            CompletableFuture<byte[]> request = bridge.channel()
                .request(1, new byte[0], Duration.ofSeconds(2));
            if (!requestReceived.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("server did not receive the active request");
            }
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    bridge.close();
                } catch (Throwable error) {
                    closeFailure.set(error);
                }
            });
            Thread.sleep(50);
            if (!closer.isAlive()) {
                throw new AssertionError("Paper bridge close returned before active requests drained");
            }
            releaseResponse.countDown();
            request.get(2, TimeUnit.SECONDS);
            closer.join(2_000);
            if (closer.isAlive()) throw new AssertionError("Paper bridge close did not finish");
            if (closeFailure.get() != null) throw new AssertionError(closeFailure.get());
            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
        } catch (Exception error) {
            throw new AssertionError(error);
        } finally {
            releaseResponse.countDown();
        }
    }

    private static Frame readFrame(DataInputStream input) throws IOException {
        if (input.readInt() != 0x4D4C4252) throw new IOException("invalid frame magic");
        input.readByte();
        int kind = input.readUnsignedByte();
        input.readUnsignedShort();
        int bodyLength = input.readInt();
        int method = input.readInt();
        long id = input.readLong();
        return new Frame(kind, method, id, input.readNBytes(bodyLength));
    }

    private static void writeFrame(
        DataOutputStream output, int kind, int method, long id, byte[] body
    ) throws IOException {
        output.writeInt(0x4D4C4252);
        output.writeByte(2);
        output.writeByte(kind);
        output.writeShort(0);
        output.writeInt(body.length);
        output.writeInt(method);
        output.writeLong(id);
        output.write(body);
        output.flush();
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == byte.class) return (byte) 0;
                if (type == short.class) return (short) 0;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                if (type == float.class) return 0F;
                if (type == double.class) return 0D;
                if (type == char.class) return '\0';
                return null;
            });
    }

    private record Frame(int kind, int method, long id, byte[] body) { }

    private static PaperDispatcher direct() {
        return (action, unavailable) -> action.run();
    }

    private static void assertUnavailable(CompletableFuture<Void> future) {
        try {
            future.join();
            throw new AssertionError("callback should have been rejected");
        } catch (CompletionException error) {
            if (!(error.getCause() instanceof PaperCall.CallbackUnavailableException)) throw error;
        }
    }
}
