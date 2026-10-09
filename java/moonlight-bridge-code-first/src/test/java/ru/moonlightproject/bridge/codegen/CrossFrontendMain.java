package ru.moonlightproject.bridge.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.tools.ToolProvider;

/** Verifies that equivalent Rust and JVM contracts have one wire schema. */
public final class CrossFrontendMain {
    private CrossFrontendMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("moonlight-cross-frontend");
        Path rustDirectory = Files.createDirectories(root.resolve("rust"));
        Path javaDirectory = Files.createDirectories(root.resolve("java"));
        Path rustSource = rustDirectory.resolve("contract.rs");
        Path rustProto = rustDirectory.resolve("contract.proto");
        Path javaSource = javaDirectory.resolve("Contract.java");
        Path javaProto = javaDirectory.resolve("contract.proto");
        Files.writeString(rustSource, """
            #[moonlight::contract(package = "moonlight.parity.v1", java_package = "example.parity")]
            mod contract {
                #[moonlight::message]
                struct PingRequest {
                    message: String,
                    attempts: Option<i32>,
                    tags: Vec<String>,
                }

                #[moonlight::message]
                struct PingResponse { message: String }

                #[moonlight::enumeration]
                enum DeliveryState { Unknown, Ready }

                #[moonlight::service]
                trait MonitoringService {
                    #[moonlight::rpc(
                        timeout_ms = 1500,
                        idle_timeout_ms = 0,
                        max_attempts = 3,
                        initial_backoff_ms = 25,
                        max_backoff_ms = 1000,
                        multiplier_milli = 2000,
                        idempotency = "idempotent",
                        max_request_bytes = 16777216,
                        max_response_bytes = 16777216,
                        required_scope = "monitoring.ping",
                        compression = "prefer",
                        trace_sample_per_million = 0
                    )]
                    async fn ping(request: PingRequest) -> PingResponse;
                }
            }
            """);
        Files.writeString(javaSource, """
            package example.parity;
            import java.util.List;
            import ru.moonlightproject.bridge.codegen.*;

            @MoonLightContract(protoPackage = "moonlight.parity.v1", javaPackage = "example.parity")
            interface Contract {
                @MoonLightMessage record PingRequest(
                    String message, Integer attempts, List<String> tags) {}
                @MoonLightMessage record PingResponse(String message) {}
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

        Process rustGenerator = new ProcessBuilder(
            System.getProperty("moonlight.codegenExecutable"),
            "source", "rust", rustSource.toString(), rustProto.toString())
            .inheritIO().start();
        check(rustGenerator.waitFor() == 0, "Rust source generation failed");
        compileJava(javaSource, javaProto);

        Path repositoryProto = Path.of(System.getProperty("moonlight.repositoryRoot"), "proto");
        Path rustDescriptor = compileProto(rustDirectory, repositoryProto, rustProto);
        Path javaDescriptor = compileProto(javaDirectory, repositoryProto, javaProto);
        byte[] rustBytes = Files.readAllBytes(rustDescriptor);
        byte[] javaBytes = Files.readAllBytes(javaDescriptor);
        check(Arrays.equals(rustBytes, javaBytes), () -> "descriptor mismatch\nRust:\n"
            + Files.readString(rustProto) + "\nJVM:\n" + Files.readString(javaProto));
    }

    private static void compileJava(Path source, Path output) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK compiler is unavailable");
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, files, null, List.of(
                "-proc:only",
                "-Amoonlight.output=" + output,
                "-classpath", System.getProperty("java.class.path")
            ), null, files.getJavaFileObjects(source));
            task.setProcessors(List.of(new MoonLightContractProcessor()));
            check(task.call(), "JVM source generation failed");
        }
    }

    private static Path compileProto(Path directory, Path repositoryProto, Path proto)
            throws Exception {
        Path descriptor = directory.resolve("contract.pb");
        Process protoc = new ProcessBuilder(
            "protoc",
            "--proto_path=" + directory,
            "--proto_path=" + repositoryProto,
            "--descriptor_set_out=" + descriptor,
            "--include_imports",
            proto.toString())
            .inheritIO().start();
        check(protoc.waitFor() == 0, "generated Protobuf did not compile");
        return descriptor;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void check(boolean condition, Message message) throws Exception {
        if (!condition) throw new AssertionError(message.get());
    }

    @FunctionalInterface
    private interface Message { String get() throws Exception; }
}
