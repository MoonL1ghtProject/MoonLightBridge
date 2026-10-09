package ru.moonlightproject.bridge.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Verifies that proto-only builds cannot reuse stale code-first output. */
public final class ProtoOnlyFunctionalMain {
    private ProtoOnlyFunctionalMain() { }

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("moonlight-proto-only");
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins { id("ru.moonlightproject.bridge") }
            """);
        Path proto = project.resolve("src/main/proto/example.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, """
            syntax = "proto3";
            package example.v1;
            option java_package = "example.v1";
            option java_multiple_files = true;
            message Current { string value = 1; }
            """);
        Path stale = project.resolve("build/moonlightBridge/schema/code-first.proto");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, """
            syntax = "proto3";
            package stale.v1;
            option java_package = "stale.v1";
            option java_multiple_files = true;
            message Stale { string value = 1; }
            """);

        var result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("generateMoonLightDescriptor", "--stacktrace")
            .build();
        var sourceTask = result.task(":generateMoonLightSourceSchema");
        check(sourceTask != null, "source-schema task was not present");
        check(sourceTask.getOutcome() == TaskOutcome.SKIPPED
            || sourceTask.getOutcome() == TaskOutcome.NO_SOURCE,
            "proto-only source task unexpectedly ran: " + sourceTask.getOutcome());
        check(!Files.exists(stale), "proto-only build retained stale generated schema");
        check(Files.isRegularFile(project.resolve("build/moonlightBridge/descriptor.pb")),
            "descriptor was not generated");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
