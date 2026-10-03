package ru.moonlightproject.bridge.client;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// Local-only probes. Assertions describe the observed bugs, not desired behavior.
public final class HardeningMain {
    record Frame(int kind, int method, long id, byte[] body) {}
    interface Action { void run(DataInputStream in, DataOutputStream out) throws Exception; }
    static Frame read(DataInputStream in) throws Exception {
        if (in.readInt() != 0x4D4C4252) throw new AssertionError();
        in.readByte(); int kind=in.readUnsignedByte(); in.readShort();
        int n=in.readInt(), method=in.readInt(); long id=in.readLong();
        return new Frame(kind,method,id,in.readNBytes(n));
    }
    static void write(DataOutputStream out,int kind,int method,long id,byte[] body) throws Exception {
        out.writeInt(0x4D4C4252); out.writeByte(1); out.writeByte(kind); out.writeShort(0);
        out.writeInt(body.length); out.writeInt(method); out.writeLong(id); out.write(body); out.flush();
    }
    static Thread serve(ServerSocket ss,int maxBody,Action action) {
        return Thread.ofPlatform().daemon().start(() -> {
            try (var s=ss.accept()) {
                s.setSoTimeout(3000);
                var in=new DataInputStream(s.getInputStream()); var out=new DataOutputStream(s.getOutputStream());
                read(in); write(out,17,0,0,ByteBuffer.allocate(16).putInt(maxBody).putInt(256).putLong(127).array());
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
        for (String test : new String[]{"welcome", "terminal", "callback", "event", "batch"}) {
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
    }
}
