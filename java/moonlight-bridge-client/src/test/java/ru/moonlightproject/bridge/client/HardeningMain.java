package ru.moonlightproject.bridge.client;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// Local-only probes. Assertions describe the observed bugs, not desired behavior.
public final class HardeningMain {
    record Frame(int kind, int flags, int method, long id, byte[] body) {}
    interface Action { void run(DataInputStream in, DataOutputStream out) throws Exception; }
    static Frame read(DataInputStream in) throws Exception {
        if (in.readInt() != 0x4D4C4252) throw new AssertionError();
        in.readByte(); int kind=in.readUnsignedByte(); int flags=in.readUnsignedShort();
        int n=in.readInt(), method=in.readInt(); long id=in.readLong();
        return new Frame(kind,flags,method,id,in.readNBytes(n));
    }
    static void write(DataOutputStream out,int kind,int method,long id,byte[] body) throws Exception {
        out.writeInt(0x4D4C4252); out.writeByte(2); out.writeByte(kind); out.writeShort(0);
        out.writeInt(body.length); out.writeInt(method); out.writeLong(id); out.write(body); out.flush();
    }
    static Thread serve(ServerSocket ss,int maxBody,Action action) {
        return serve(ss, maxBody, 16*1024*1024, 1, action);
    }
    static Thread serve(ServerSocket ss,int maxBody,int maxDecoded,int codecs,Action action) {
        return Thread.ofPlatform().daemon().start(() -> {
            try (var s=ss.accept()) {
                s.setSoTimeout(3000);
                var in=new DataInputStream(s.getInputStream()); var out=new DataOutputStream(s.getOutputStream());
                read(in); write(out,17,0,0,ByteBuffer.allocate(44)
                    .putInt(maxBody).putInt(maxDecoded).putInt(16*1024).putInt(256)
                    .putInt(64).putInt(32).putInt(codecs).putLong(127).putLong(0).array());
                action.run(in,out);
            } catch (EOFException | SocketException expected) { }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    static MoonLightClient client(ServerSocket ss) throws Exception {
        return MoonLightClient.connect("tcp://127.0.0.1:"+ss.getLocalPort(),
            MoonLightPerformanceOptions.automatic("tcp"),MoonLightTelemetry.disabled());
    }
    static void check(boolean value,String description) {
        if (!value) throw new AssertionError(description);
        System.out.println("PASSED: "+description);
    }
    public static void main(String[] args) throws Exception {
        var failures = new java.util.ArrayList<Throwable>();
        String[] tests = args.length == 0
            ? new String[]{"welcome", "terminal", "callback", "event", "batch", "compression", "compression-limit", "compression-wire-limit", "rpc-policy", "interceptor-order", "interceptor-short-circuit", "interceptor-cancel", "drain"}
            : args;
        for (String test : tests) {
            try { run(test); } catch(Throwable e) { failures.add(e); e.printStackTrace(); }
        }
        if (!failures.isEmpty()) throw new AssertionError("hardening failures: " + failures.size());
    }
    static void run(String test) throws Exception {
        if (test.equals("welcome")) {
            try(var ss=new ServerSocket(0)) {
                serve(ss,64*1024*1024,(in,out)->{while(read(in).kind()!=21) {}});
                boolean rejected=false;
                try(var c=client(ss)) {} catch(IOException expected) { rejected=true; }
                check(rejected,"WELCOME cannot raise advertised ceiling");
            }
        }
        if (test.equals("terminal") || test.equals("callback")) {
            var errors=new AtomicInteger();
            var stream=new MoonLightServerStream<byte[]>(n->{},()->{},e->{});
            stream.subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription s){if(test.equals("terminal")) s.request(0);}
                public void onNext(byte[] x){}
                public void onError(Throwable e){errors.incrementAndGet();}
                public void onComplete(){throw new IllegalStateException("subscriber failed");}
            });
            if(test.equals("terminal")) check(errors.get()==1,"invalid demand sends exactly one error");
            else { stream.complete(); check(true,"subscriber terminal exception isolated"); }
        }
        if (test.equals("event")) {
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
            try(var ss=new ServerSocket(0)) {
                serve(ss,8*1024*1024,(in,out)->{
                    Frame first=read(in); write(out,24,7,0,new byte[0]); write(out,20,0,first.id(),first.body());
                    while(true){Frame f=read(in);if(f.kind()==21)break;if(f.kind()==19)write(out,20,0,f.id(),f.body());}
                });
                try(var c=client(ss)) {
                    c.subscribe(7,b->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                    var ping=c.ping(Duration.ofSeconds(2));
                    check(entered.await(1,TimeUnit.SECONDS),"listener entered");
                    try { ping.get(1,TimeUnit.SECONDS); } finally { release.countDown(); }
                }
            }
        }
        if(test.equals("batch")) {
            var source=new CompletableFuture<byte[]>();
            var channel=new MoonLightChannel(){
                public CompletableFuture<byte[]> request(int m,byte[] b,Duration d){return source;}
                public CompletableFuture<Void> ping(Duration d){return CompletableFuture.completedFuture(null);}
                public void close(){}
            };
            channel.requestBatch(java.util.List.of(new MoonLightRequest(1,new byte[0],Duration.ofSeconds(1)))).cancel(true);
            check(source.isCancelled(),"batch cancellation reaches children");
        }
        if(test.equals("compression")) {
            var options = new MoonLightCompression.Options(32, 8, 1024 * 1024, 64, 1);
            var budget = new MoonLightCompression.DecodedByteBudget(1024 * 1024);
            byte[] payload = new byte[4096];
            int state = 0x13579bdf;
            for (int index = 0; index < payload.length / 2; index++) {
                state = state * 1664525 + 1013904223;
                payload[index] = (byte) (state >>> 24);
                payload[index + payload.length / 2] = payload[index];
            }
            var encoded = MoonLightCompression.encode(
                payload, MoonLightCompression.Codec.ZSTD, options);
            check(encoded.codec() == MoonLightCompression.Codec.ZSTD,
                "compression retains useful bounded output");
            check(java.util.Arrays.equals(
                MoonLightCompression.decode(encoded, options, budget), payload),
                "bounded zstd round trip");
            var below = MoonLightCompression.encode(
                new byte[31], MoonLightCompression.Codec.ZSTD, options);
            check(below.codec() == MoonLightCompression.Codec.NONE,
                "compression threshold avoids small payload overhead");
            expectIOException(() -> MoonLightCompression.decode(
                new MoonLightCompression.Encoded(MoonLightCompression.Codec.ZSTD, 128,
                    "not-zstd".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                options, budget));
            expectIOException(() -> MoonLightCompression.decode(
                new MoonLightCompression.Encoded(MoonLightCompression.Codec.ZSTD,
                    options.maxDecodedBodyLength() + 1, new byte[] {1}), options, budget));
            expectIOException(() -> MoonLightCompression.decode(
                new MoonLightCompression.Encoded(MoonLightCompression.Codec.ZSTD,
                    129, new byte[] {1, 2}), options, budget));
            try (var ignored = budget.reserve(options.maxDecodedBodyLength())) {
                expectIOException(() -> MoonLightCompression.decode(encoded, options, budget));
            }
            check(true, "compression rejects corrupt, oversized, and over-budget payloads");
        }
        if(test.equals("compression-limit")) {
            try(var ss=new ServerSocket(0)) {
                serve(ss, 8*1024*1024, 1024, 3, (in,out)->{
                    while(read(in).kind()!=21) { }
                });
                try(var c=client(ss)) {
                    try {
                        c.request(1, new byte[4096], Duration.ofSeconds(1)).get();
                        throw new AssertionError("negotiated decoded limit was not enforced");
                    } catch (ExecutionException expected) {
                        check(expected.getCause() instanceof IllegalArgumentException,
                            "compression honors negotiated decoded limit");
                    }
                }
            }
        }
        if(test.equals("compression-wire-limit")) {
            var decoded = new AtomicBoolean();
            try(var ss=new ServerSocket(0)) {
                serve(ss, 512, 4096, 3, (in,out)->{
                    Frame request=read(in);
                    var metadata=MoonLightMetadata.decode(
                        request.body(), new MoonLightMetadata.Limits(16*1024,64,8*1024));
                    byte[] codec=metadata.metadata().get(MoonLightMetadata.ReservedKey.COMPRESSION_CODEC);
                    byte[] original=metadata.metadata().get(MoonLightMetadata.ReservedKey.ORIGINAL_LENGTH);
                    byte[] restored=MoonLightCompression.decode(
                        MoonLightCompression.transportEncoded(
                            MoonLightCompression.Codec.fromWire(Byte.toUnsignedInt(codec[0])),
                            ByteBuffer.wrap(original).getInt(), metadata.payload()),
                        new MoonLightCompression.Options(32,8,4096,64,1),
                        new MoonLightCompression.DecodedByteBudget(8192));
                    decoded.set(request.flags() == 1 && restored.length == 4096);
                    write(out,2,request.method(),request.id(),new byte[0]);
                    while(read(in).kind()!=21) { }
                });
                try(var c=client(ss)) {
                    byte[] payload=new byte[4096];
                    for(int index=0;index<payload.length;index++) payload[index]=(byte)(index%128);
                    c.request(1,payload,Duration.ofSeconds(1)).get();
                    check(decoded.get(),"compression permits decoded payload above wire limit");
                }
            }
        }
        if(test.equals("rpc-policy")) {
            var policy=new RpcPolicy(
                Duration.ofSeconds(2),Duration.ZERO,
                new RpcPolicy.Retry(3,Duration.ofMillis(25),Duration.ofMillis(250),2000),
                RpcPolicy.Idempotency.IDEMPOTENT,4096,8192,
                java.util.Set.of("player.read"),RpcPolicy.Compression.PREFER,25000);
            check(policy.effectiveDeadline(Duration.ofSeconds(5)).equals(Duration.ofSeconds(2)),
                "RPC deadline override cannot weaken schema timeout");
            check(policy.effectiveDeadline(Duration.ofMillis(500)).equals(Duration.ofMillis(500)),
                "RPC deadline override may tighten schema timeout");
            expectIllegalArgument(() -> new RpcPolicy(
                Duration.ZERO,Duration.ZERO,policy.retry(),policy.idempotency(),4096,8192,
                java.util.Set.of(),policy.compression(),0));
            check(true,"RPC policy rejects unsafe ranges");

            var required=new RpcPolicy(policy.timeout(),policy.idleTimeout(),policy.retry(),
                policy.idempotency(),policy.maxRequestBytes(),policy.maxResponseBytes(),
                policy.requiredScopes(),RpcPolicy.Compression.REQUIRED,policy.traceSamplePerMillion());
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,8192,1,(in,out)->{while(read(in).kind()!=21) { }});
                try(var c=client(ss)) {
                    try {
                        c.request(1,new byte[32],Duration.ofSeconds(1),required).get();
                        throw new AssertionError("required compression was not enforced");
                    } catch(ExecutionException expected) {
                        check(expected.getCause() instanceof IOException,
                            "required RPC compression fails without negotiation");
                    }
                }
            }

            var disabledSeen=new AtomicBoolean();
            var disabled=new RpcPolicy(policy.timeout(),policy.idleTimeout(),policy.retry(),
                policy.idempotency(),policy.maxRequestBytes(),policy.maxResponseBytes(),
                policy.requiredScopes(),RpcPolicy.Compression.DISABLED,policy.traceSamplePerMillion());
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,8192,3,(in,out)->{
                    Frame request=read(in);
                    var metadata=MoonLightMetadata.decode(
                        request.body(),new MoonLightMetadata.Limits(16*1024,64,8*1024));
                    disabledSeen.set(metadata.metadata().get(
                        MoonLightMetadata.ReservedKey.COMPRESSION_CODEC)==null
                        && metadata.payload().length==4096);
                    write(out,2,request.method(),request.id(),new byte[0]);
                    while(read(in).kind()!=21) { }
                });
                try(var c=client(ss)) {
                    c.request(1,new byte[4096],Duration.ofSeconds(1),disabled).get();
                    check(disabledSeen.get(),"disabled RPC compression bypasses negotiated codec");
                }
            }
        }
        if(test.equals("interceptor-order")) {
            var events=new CopyOnWriteArrayList<String>();
            var policy=new RpcPolicy(
                Duration.ofSeconds(2),Duration.ZERO,
                new RpcPolicy.Retry(1,Duration.ofMillis(25),Duration.ofMillis(25),1000),
                RpcPolicy.Idempotency.READ_ONLY,4096,4096,
                java.util.Set.of("echo.invoke"),RpcPolicy.Compression.DISABLED,0);
            MoonLightInterceptor outer=new MoonLightInterceptor(){
                public MoonLightCallContext beforeCall(MoonLightCallContext context){
                    events.add("outer:before");
                    check(context.requiredScopes().equals(java.util.Set.of("echo.invoke")),
                        "interceptor sees generated required scopes");
                    check(context.deadline().equals(Duration.ofSeconds(2)),
                        "interceptor sees effective generated deadline");
                    return context.withDeadline(Duration.ofMillis(500)).withMetadata(
                        MoonLightMetadata.builder().put("request-origin",
                            "test".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).build());
                }
                public void afterCall(MoonLightCallContext context,byte[] response,Throwable failure){
                    events.add("outer:after");
                }
            };
            MoonLightInterceptor inner=new MoonLightInterceptor(){
                public MoonLightCallContext beforeCall(MoonLightCallContext context){
                    events.add("inner:before"); return context;
                }
                public void afterCall(MoonLightCallContext context,byte[] response,Throwable failure){
                    events.add("inner:after"); throw new IllegalStateException("isolated");
                }
            };
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,(in,out)->{
                    Frame request=read(in);
                    var decoded=MoonLightMetadata.decode(
                        request.body(),new MoonLightMetadata.Limits(16*1024,64,8*1024));
                    check(java.util.Arrays.equals(decoded.metadata().get("request-origin"),
                        "test".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                        "interceptor metadata reaches the wire");
                    check(ByteBuffer.wrap(decoded.metadata().get(
                        MoonLightMetadata.ReservedKey.DEADLINE_MILLIS)).getInt()==500,
                        "interceptor may tighten the wire deadline");
                    write(out,2,request.method(),request.id(),decoded.payload());
                    while(read(in).kind()!=21) { }
                });
                try(var c=MoonLightClient.connect("tcp://127.0.0.1:"+ss.getLocalPort(),
                    MoonLightPerformanceOptions.automatic("tcp"),MoonLightTelemetry.disabled(),
                    java.util.List.of(outer,inner))) {
                    byte[] response=c.request(1,"ok".getBytes(),Duration.ofSeconds(5),policy).get();
                    check(java.util.Arrays.equals(response,"ok".getBytes()),
                        "interceptor terminal exception is isolated");
                    check(events.equals(java.util.List.of(
                        "outer:before","inner:before","inner:after","outer:after")),
                        "interceptors wrap calls in deterministic order");
                }
            }
        }
        if(test.equals("interceptor-short-circuit")) {
            var calls=new AtomicInteger();
            MoonLightInterceptor reject=new MoonLightInterceptor(){
                public MoonLightCallContext beforeCall(MoonLightCallContext context){
                    calls.incrementAndGet(); throw new SecurityException("denied");
                }
                public void afterCall(MoonLightCallContext context,byte[] response,Throwable failure){
                    calls.incrementAndGet();
                }
            };
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,(in,out)->{ while(read(in).kind()!=21) { throw new AssertionError("short-circuited call reached wire"); } });
                try(var c=MoonLightClient.connect("tcp://127.0.0.1:"+ss.getLocalPort(),
                    MoonLightPerformanceOptions.automatic("tcp"),MoonLightTelemetry.disabled(),
                    java.util.List.of(reject))) {
                    try { c.request(1,new byte[0],Duration.ofSeconds(1)).get();
                        throw new AssertionError("interceptor did not short-circuit");
                    } catch(ExecutionException expected) {
                        check(expected.getCause() instanceof SecurityException,
                            "interceptor short-circuit preserves failure");
                    }
                    check(calls.get()==2,"short-circuit emits one terminal interceptor callback");
                }
            }
        }
        if(test.equals("interceptor-cancel")) {
            var terminals=new AtomicInteger();
            MoonLightInterceptor interceptor=new MoonLightInterceptor(){
                public MoonLightCallContext beforeCall(MoonLightCallContext context){return context;}
                public void afterCall(MoonLightCallContext context,byte[] response,Throwable failure){
                    terminals.incrementAndGet();
                }
            };
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,(in,out)->{Frame request=read(in); while(read(in).kind()!=18) { }});
                try(var c=MoonLightClient.connect("tcp://127.0.0.1:"+ss.getLocalPort(),
                    MoonLightPerformanceOptions.automatic("tcp"),MoonLightTelemetry.disabled(),
                    java.util.List.of(interceptor))) {
                    var future=c.request(1,new byte[0],Duration.ofSeconds(2));
                    future.cancel(true);
                    Thread.sleep(50);
                    check(terminals.get()==1,"cancellation emits one terminal interceptor callback");
                }
            }
        }
        if(test.equals("drain")) {
            var release=new CountDownLatch(1);
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,(in,out)->{
                    Frame request=read(in);
                    release.await();
                    write(out,2,request.method(),request.id(),request.body());
                    while(read(in).kind()!=21) { }
                });
                try(var c=client(ss)) {
                    var existing=c.request(1,new byte[0],Duration.ofSeconds(2));
                    MoonLightDrainHandle drain=c.drain(Duration.ofSeconds(1));
                    try { c.request(1,new byte[0],Duration.ofSeconds(1)).get();
                        throw new AssertionError("draining client admitted a new call");
                    } catch(ExecutionException expected) {
                        check(expected.getCause() instanceof RejectedExecutionException,
                            "client drain rejects new calls");
                    }
                    release.countDown();
                    existing.get();
                    drain.completion().toCompletableFuture().get(1,TimeUnit.SECONDS);
                    check(drain.isDone(),"client drain completes after existing calls");
                }
            }
            var releaseRemote=new CountDownLatch(1);
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,(in,out)->{
                    Frame request=read(in);
                    write(out,28,0,0,new byte[0]);
                    releaseRemote.await();
                    write(out,2,request.method(),request.id(),request.body());
                    while(read(in).kind()!=21) { }
                });
                try(var c=client(ss)) {
                    var existing=c.request(1,new byte[0],Duration.ofSeconds(2));
                    Thread.sleep(50);
                    try { c.request(1,new byte[0],Duration.ofSeconds(1)).get();
                        throw new AssertionError("GOAWAY did not stop admission");
                    } catch(ExecutionException expected) {
                        check(expected.getCause() instanceof RejectedExecutionException,
                            "remote GOAWAY rejects new calls");
                    }
                    releaseRemote.countDown();
                    existing.get();
                }
            }
            var terminals=new AtomicInteger();
            MoonLightInterceptor interceptor=new MoonLightInterceptor(){
                public MoonLightCallContext beforeCall(MoonLightCallContext context){return context;}
                public void afterCall(MoonLightCallContext context,byte[] response,Throwable failure){
                    terminals.incrementAndGet();
                }
            };
            try(var ss=new ServerSocket(0)) {
                serve(ss,8192,(in,out)->{ read(in); while(read(in).kind()!=21) { } });
                try(var c=MoonLightClient.connect("tcp://127.0.0.1:"+ss.getLocalPort(),
                    MoonLightPerformanceOptions.automatic("tcp"),MoonLightTelemetry.disabled(),
                    java.util.List.of(interceptor))) {
                    var pending=c.request(1,new byte[0],Duration.ofSeconds(2));
                    MoonLightDrainHandle drain=c.drain(Duration.ofMillis(10));
                    try { drain.completion().toCompletableFuture().get(1,TimeUnit.SECONDS);
                        throw new AssertionError("drain timeout completed successfully");
                    } catch(ExecutionException expected) {
                        check(expected.getCause() instanceof TimeoutException,
                            "client drain timeout is reported");
                    }
                    try { pending.get(1,TimeUnit.SECONDS); }
                    catch(ExecutionException expected) { }
                    check(terminals.get()==1,"drain timeout emits one terminal callback");
                }
            }
        }
    }

    interface IoAction { void run() throws Exception; }
    static void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("compression failure was not rejected");
        } catch (IOException expected) { }
    }
    static void expectIllegalArgument(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("invalid RPC policy was not rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
