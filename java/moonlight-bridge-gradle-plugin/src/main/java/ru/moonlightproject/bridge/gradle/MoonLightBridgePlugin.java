package ru.moonlightproject.bridge.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.JavaCompile;

/** Convention plugin for the repeatable Java side of MoonLightBridge code generation. */
public final class MoonLightBridgePlugin implements Plugin<Project> {
    /** Creates the stateless Gradle plugin. */
    public MoonLightBridgePlugin() { }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        MoonLightBridgeExtension extension = project.getExtensions().create(
            "moonlightBridge", MoonLightBridgeExtension.class);
        extension.getProtoDirectory().convention(project.getLayout().getProjectDirectory().dir("src/main/proto"));
        extension.getContractSourceDirectory().convention(
            project.getLayout().getProjectDirectory().dir("src/main/moonlightContract"));
        extension.getSchemaLock().convention(project.getLayout().getProjectDirectory().file("schema.lock"));
        extension.getGeneratedSources().convention(project.getLayout().getBuildDirectory().dir("generated/sources/moonlightBridge/java"));
        extension.getGeneratedProtoSources().convention(project.getLayout().getBuildDirectory().dir("generated/sources/moonlightBridge/protoJava"));
        extension.getGeneratedSchema().convention(
            project.getLayout().getBuildDirectory().file("moonlightBridge/schema/code-first.proto"));
        extension.getDescriptorFile().convention(project.getLayout().getBuildDirectory().file("moonlightBridge/descriptor.pb"));
        extension.getProtocExecutable().convention("protoc");
        extension.getCodegenExecutable().convention("moonlight-bridge-codegen");

        var bundledProtoRoot = project.getLayout().getBuildDirectory().dir("moonlightBridge/includes");
        var bundledOptions = bundledProtoRoot.map(directory -> directory.file(
            "moonlight/bridge/options/v1/options.proto"));
        var prepareOptions = project.getTasks().register("prepareMoonLightOptions", task -> {
            task.setGroup("moonlight bridge");
            task.setDescription("Extracts the bundled MoonLightBridge RPC option schema");
            task.getOutputs().file(bundledOptions);
            task.doLast(ignored -> {
                File output = bundledOptions.get().getAsFile();
                createDirectories(output.getParentFile());
                try (var source = MoonLightBridgePlugin.class.getResourceAsStream(
                    "/moonlight-bridge-proto/moonlight/bridge/options/v1/options.proto")) {
                    if (source == null) throw new IOException("bundled options.proto is missing");
                    Files.copy(source, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException error) {
                    throw new GradleException("Cannot extract MoonLightBridge options.proto", error);
                }
            });
        });

        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        var mainSourceSet = sourceSets.getByName("main");
        var sourceSchema = project.getTasks().register(
            "generateMoonLightSourceSchema", JavaCompile.class, task -> {
                task.setGroup("moonlight bridge");
                task.setDescription("Generates Protobuf from annotated Java contract sources");
                task.source(project.fileTree(extension.getContractSourceDirectory())
                    .matching(pattern -> pattern.include("**/*.java")));
                task.setClasspath(mainSourceSet.getCompileClasspath());
                task.getOptions().setAnnotationProcessorPath(
                    project.getConfigurations().getByName("annotationProcessor"));
                task.getDestinationDirectory().set(
                    project.getLayout().getBuildDirectory().dir("moonlightBridge/contractClasses"));
                task.getOutputs().file(extension.getGeneratedSchema());
                task.onlyIf(ignored -> !task.getSource().isEmpty());
                task.doFirst(ignored -> {
                    createDirectories(extension.getGeneratedSchema().get().getAsFile().getParentFile());
                    task.getOptions().getCompilerArgs().add("-proc:only");
                    task.getOptions().getCompilerArgs().add(
                        "-Amoonlight.output=" + extension.getGeneratedSchema().get().getAsFile());
                    task.getOptions().getCompilerArgs().add(
                        "-Amoonlight.schemaLock=" + extension.getSchemaLock().get().getAsFile());
                });
            });

        var descriptor = project.getTasks().register("generateMoonLightDescriptor", Exec.class, task -> {
            task.setGroup("moonlight bridge");
            task.setDescription("Compiles protobuf sources into a descriptor set");
            task.dependsOn(prepareOptions, sourceSchema);
            task.getInputs().dir(extension.getProtoDirectory());
            task.getInputs().file(bundledOptions);
            task.getOutputs().file(extension.getDescriptorFile());
            task.getOutputs().dir(extension.getGeneratedProtoSources());
            task.doFirst(ignored -> {
                if (sourceSchema.get().getSource().isEmpty()) {
                    try {
                        Files.deleteIfExists(extension.getGeneratedSchema().get().getAsFile().toPath());
                    } catch (IOException error) {
                        throw new GradleException("Cannot remove stale code-first schema", error);
                    }
                }
                File output = extension.getDescriptorFile().get().getAsFile();
                createDirectories(output.getParentFile());
                createDirectories(extension.getGeneratedProtoSources().get().getAsFile());
                List<String> command = new ArrayList<>();
                command.add(extension.getProtocExecutable().get());
                command.add("--proto_path=" + extension.getProtoDirectory().get().getAsFile());
                command.add("--proto_path=" + extension.getGeneratedSchema().get().getAsFile().getParentFile());
                command.add("--proto_path=" + bundledProtoRoot.get().getAsFile());
                command.add("--include_imports");
                command.add("--java_out=" + extension.getGeneratedProtoSources().get().getAsFile());
                command.add("--descriptor_set_out=" + output);
                List<File> sources = project.fileTree(extension.getProtoDirectory())
                    .matching(pattern -> pattern.include("**/*.proto"))
                    .getFiles().stream().sorted().toList();
                sources.forEach(file -> command.add(file.getAbsolutePath()));
                boolean suppliesOptions = sources.stream().anyMatch(file -> file.toPath().endsWith(
                    "moonlight/bridge/options/v1/options.proto"));
                if (!suppliesOptions) command.add(bundledOptions.get().getAsFile().getAbsolutePath());
                if (extension.getGeneratedSchema().get().getAsFile().isFile()) {
                    command.add(extension.getGeneratedSchema().get().getAsFile().getAbsolutePath());
                }
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

        mainSourceSet.getJava().srcDir(extension.getGeneratedSources());
        mainSourceSet.getJava().srcDir(extension.getGeneratedProtoSources());
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
