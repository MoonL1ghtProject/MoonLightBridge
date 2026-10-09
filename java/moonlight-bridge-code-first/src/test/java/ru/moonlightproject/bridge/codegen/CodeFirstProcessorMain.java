package ru.moonlightproject.bridge.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

public final class CodeFirstProcessorMain {
    private CodeFirstProcessorMain() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("moonlight-code-first-java");
        Path source = directory.resolve("MonitoringContract.java");
        Path output = directory.resolve("monitoring.proto");
        Path lock = directory.resolve("schema.lock");
        Files.writeString(source, """
            package example.monitoring;
            import ru.moonlightproject.bridge.codegen.*;
            import java.util.List;

            @MoonLightContract(
                protoPackage = "moonlight.monitoring.v1",
                javaPackage = "example.monitoring.generated")
            interface MonitoringContract {
                @MoonLightMessage record PingRequest(String message, Integer attempts, List<String> tags) {}
                @MoonLightMessage record PingResponse(String message, long handledAtUnixMs) {}
                @MoonLightMessage final class KotlinPlayer {
                    private final String displayName = "";
                }
                @MoonLightEnumeration enum DeliveryState { UNKNOWN, READY }

                @MoonLightService interface MonitoringService {
                    @MoonLightRpc(
                        timeoutMs = 1500,
                        maxAttempts = 3,
                        idempotency = "idempotent",
                        requiredScopes = {"monitoring.ping"},
                        compression = "prefer")
                    PingResponse ping(PingRequest request);
                }
            }
            """);
        Files.writeString(lock, """
            {
              "format": 2,
              "messages": {
                "moonlight.monitoring.v1.PingRequest": {
                  "fields": {
                    "8": {"name":"message","number":8,"label":1,"kind":9,"type_name":"","oneof_index":null,"proto3_optional":false}
                  },
                  "reserved_ranges": [{"start":9,"end":10}],
                  "reserved_names": ["old_tag"]
                }
              },
              "enums": {
                "moonlight.monitoring.v1.DeliveryState": {
                  "values": {"0":"UNKNOWN","4":"READY","7":"LEGACY"},
                  "reserved_ranges": [{"start":9,"end":10}],
                  "reserved_names": ["DEPRECATED"]
                }
              },
              "services": {}
            }
            """);

        var compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK compiler is unavailable");
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var units = files.getJavaFileObjects(source);
            var task = compiler.getTask(null, files, null, List.of(
                "-proc:only",
                "-Amoonlight.output=" + output,
                "-Amoonlight.schemaLock=" + lock,
                "-classpath", System.getProperty("java.class.path")
            ), null, units);
            task.setProcessors(List.of(new MoonLightContractProcessor()));
            check(task.call(), "annotation processing failed");
        }

        String proto = Files.readString(output);
        check(proto.contains("package moonlight.monitoring.v1;"), proto);
        check(proto.contains("string message = 8;"), proto);
        check(proto.contains("optional int32 attempts = 1;"), proto);
        check(proto.contains("repeated string tags = 2;"), proto);
        check(proto.contains("reserved 9;"), proto);
        check(proto.contains("reserved \"old_tag\";"), proto);
        check(proto.contains("int64 handled_at_unix_ms = 2;"), proto);
        check(proto.contains("string display_name = 1;"), proto);
        check(proto.contains("UNKNOWN = 0;"), proto);
        check(proto.contains("READY = 4;"), proto);
        check(proto.contains("reserved 7;"), proto);
        check(proto.contains("reserved \"LEGACY\";"), proto);
        check(proto.contains("reserved 9;"), proto);
        check(proto.contains("reserved \"DEPRECATED\";"), proto);
        check(proto.contains("rpc Ping(PingRequest) returns (PingResponse)"), proto);
        check(proto.contains("required_scopes: \"monitoring.ping\""), proto);
        Process protoc = new ProcessBuilder(
            "protoc",
            "--proto_path=" + directory,
            "--proto_path=" + Path.of(System.getProperty("moonlight.repositoryRoot"), "proto"),
            "--descriptor_set_out=" + directory.resolve("monitoring.pb"),
            "--include_imports",
            output.toString())
            .inheritIO()
            .start();
        check(protoc.waitFor() == 0, "generated Protobuf did not compile");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
