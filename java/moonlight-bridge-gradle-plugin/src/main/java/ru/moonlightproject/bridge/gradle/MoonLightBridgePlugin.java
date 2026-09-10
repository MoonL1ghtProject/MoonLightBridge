package ru.moonlightproject.bridge.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.SourceSetContainer;

/** Convention plugin for the repeatable Java side of MoonLightBridge code generation. */
public final class MoonLightBridgePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        MoonLightBridgeExtension extension = project.getExtensions().create(
            "moonlightBridge", MoonLightBridgeExtension.class);
        extension.getProtoDirectory().convention(project.getLayout().getProjectDirectory().dir("src/main/proto"));
        extension.getSchemaLock().convention(project.getLayout().getProjectDirectory().file("schema.lock"));
        extension.getGeneratedSources().convention(project.getLayout().getBuildDirectory().dir("generated/sources/moonlightBridge/java"));
        extension.getGeneratedProtoSources().convention(project.getLayout().getBuildDirectory().dir("generated/sources/moonlightBridge/protoJava"));
        extension.getDescriptorFile().convention(project.getLayout().getBuildDirectory().file("moonlightBridge/descriptor.pb"));
        extension.getProtocExecutable().convention("protoc");
        extension.getCodegenExecutable().convention("moonlight-bridge-codegen");

        var descriptor = project.getTasks().register("generateMoonLightDescriptor", Exec.class, task -> {
            task.setGroup("moonlight bridge");
            task.setDescription("Compiles protobuf sources into a descriptor set");
            task.getInputs().dir(extension.getProtoDirectory());
            task.getOutputs().file(extension.getDescriptorFile());
            task.getOutputs().dir(extension.getGeneratedProtoSources());
            task.doFirst(ignored -> {
                File output = extension.getDescriptorFile().get().getAsFile();
                createDirectories(output.getParentFile());
                createDirectories(extension.getGeneratedProtoSources().get().getAsFile());
                List<String> command = new ArrayList<>();
                command.add(extension.getProtocExecutable().get());
                command.add("--proto_path=" + extension.getProtoDirectory().get().getAsFile());
                command.add("--include_imports");
                command.add("--java_out=" + extension.getGeneratedProtoSources().get().getAsFile());
                command.add("--descriptor_set_out=" + output);
                project.fileTree(extension.getProtoDirectory()).matching(pattern -> pattern.include("**/*.proto"))
                    .getFiles().stream().sorted().forEach(file -> command.add(file.getAbsolutePath()));
                task.commandLine(command);
            });
        });

        var generate = project.getTasks().register("generateMoonLightBridge", Exec.class, task -> {
            task.setGroup("moonlight bridge");
            task.setDescription("Generates type-safe MoonLightBridge Java clients");
            task.dependsOn(descriptor);
            task.getInputs().file(extension.getDescriptorFile());
            task.getOutputs().dir(extension.getGeneratedSources());
            task.doFirst(ignored -> task.commandLine(
                extension.getCodegenExecutable().get(),
                extension.getDescriptorFile().get().getAsFile(),
                extension.getGeneratedSources().get().getAsFile()));
        });

        var checkSchema = project.getTasks().register("checkMoonLightSchema", Exec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Rejects incompatible protobuf schema changes");
            task.dependsOn(descriptor);
            task.doFirst(ignored -> task.commandLine(
                extension.getCodegenExecutable().get(), "lock",
                extension.getDescriptorFile().get().getAsFile(),
                extension.getSchemaLock().get().getAsFile(), "--check"));
        });

        project.getExtensions().getByType(SourceSetContainer.class).getByName("main")
            .getJava().srcDir(extension.getGeneratedSources());
        project.getExtensions().getByType(SourceSetContainer.class).getByName("main")
            .getJava().srcDir(extension.getGeneratedProtoSources());
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME).configure(task -> task.dependsOn(generate));
        project.getTasks().named("check").configure(task -> task.dependsOn(checkSchema));
    }

    private static void createDirectories(File directory) {
        try {
            Files.createDirectories(directory.toPath());
        } catch (IOException error) {
            throw new GradleException("Cannot create MoonLightBridge output directory: " + directory, error);
        }
    }
}
