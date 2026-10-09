plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":java:moonlight-bridge-client"))
    implementation(project(":java:moonlight-bridge-micrometer"))
    implementation(project(":java:moonlight-bridge-otel"))
    implementation("com.google.protobuf:protobuf-java:4.36.1")
    implementation("io.micrometer:micrometer-registry-prometheus:1.17.1")
}

val contractSource = rootProject.layout.projectDirectory.file("examples/observability-backend/src/contract.rs")
val schemaLock = rootProject.layout.projectDirectory.file("examples/observability/schema.lock")
val generatedSchema = layout.buildDirectory.file("generated/schema/monitoring.proto")
val descriptor = layout.buildDirectory.file("generated/schema/monitoring.pb")
val generatedProtoJava = layout.buildDirectory.dir("generated/sources/proto/java")
val generatedBridgeJava = layout.buildDirectory.dir("generated/sources/moonlight/java")
val codegenExecutable = rootProject.layout.projectDirectory.file("target/debug/moonlight-bridge-codegen")

val buildCodegen = tasks.register<Exec>("buildCodegen") {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--package", "moonlight-bridge-codegen")
}

val generateSourceSchema = tasks.register<Exec>("generateSourceSchema") {
    dependsOn(buildCodegen)
    inputs.file(contractSource)
    inputs.file(schemaLock)
    outputs.file(generatedSchema)
    doFirst { generatedSchema.get().asFile.parentFile.mkdirs() }
    commandLine(
        codegenExecutable.asFile.absolutePath,
        "source", "rust",
        contractSource.asFile.absolutePath,
        generatedSchema.get().asFile.absolutePath,
        schemaLock.asFile.absolutePath,
    )
}

val generateDescriptor = tasks.register<Exec>("generateDescriptor") {
    dependsOn(generateSourceSchema)
    inputs.file(generatedSchema)
    outputs.file(descriptor)
    outputs.dir(generatedProtoJava)
    doFirst {
        descriptor.get().asFile.parentFile.mkdirs()
        generatedProtoJava.get().asFile.mkdirs()
    }
    commandLine(
        "protoc",
        "--proto_path=${generatedSchema.get().asFile.parentFile}",
        "--include_imports",
        "--java_out=${generatedProtoJava.get().asFile}",
        "--descriptor_set_out=${descriptor.get().asFile}",
        generatedSchema.get().asFile.absolutePath,
    )
}

val generateBridge = tasks.register<Exec>("generateBridge") {
    dependsOn(generateDescriptor)
    inputs.file(descriptor)
    outputs.dir(generatedBridgeJava)
    doFirst { generatedBridgeJava.get().asFile.mkdirs() }
    commandLine(
        codegenExecutable.asFile.absolutePath,
        descriptor.get().asFile.absolutePath,
        generatedBridgeJava.get().asFile.absolutePath,
    )
}

sourceSets.main {
    java.srcDir(generatedProtoJava)
    java.srcDir(generatedBridgeJava)
}
tasks.compileJava { dependsOn(generateBridge) }

application {
    mainClass = "ru.moonlightproject.bridge.monitoring.MonitoringClientMain"
}
