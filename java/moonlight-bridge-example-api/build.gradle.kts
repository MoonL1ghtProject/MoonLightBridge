plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":java:moonlight-bridge-client"))
    api("com.google.protobuf:protobuf-java:4.36.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val generatedProtoDirectory = layout.buildDirectory.dir("generated/sources/proto/main/java")
val generatedServiceDirectory = layout.buildDirectory.dir("generated/sources/moonlight-bridge/main/java")
val descriptorFile = layout.buildDirectory.file("generated/descriptors/example.pb")

val generateProto = tasks.register<Exec>("generateProto") {
    val schema = rootProject.file("proto/moonlight/bridge/example/v1/echo.proto")
    inputs.file(schema)
    outputs.dir(generatedProtoDirectory)
    outputs.file(descriptorFile)
    doFirst {
        generatedProtoDirectory.get().asFile.mkdirs()
        descriptorFile.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "protoc",
        "--proto_path=${rootProject.file("proto")}",
        "--java_out=${generatedProtoDirectory.get().asFile}",
        "--descriptor_set_out=${descriptorFile.get().asFile}",
        "--include_imports",
        schema.absolutePath,
    )
}

val generateMoonLightServices = tasks.register<Exec>("generateMoonLightServices") {
    dependsOn("checkSchemaCompatibility")
    inputs.file(descriptorFile)
    inputs.files(rootProject.fileTree("crates/moonlight-bridge-codegen"))
    outputs.dir(generatedServiceDirectory)
    doFirst { generatedServiceDirectory.get().asFile.mkdirs() }
    workingDir(rootProject.projectDir)
    commandLine(
        "cargo", "run", "--quiet", "--package", "moonlight-bridge-codegen", "--",
        descriptorFile.get().asFile.absolutePath,
        generatedServiceDirectory.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("checkSchemaCompatibility") {
    dependsOn(generateProto)
    inputs.file(descriptorFile)
    inputs.file(rootProject.file("proto/schema.lock"))
    inputs.files(rootProject.fileTree("crates/moonlight-bridge-codegen"))
    workingDir(rootProject.projectDir)
    commandLine(
        "cargo", "run", "--quiet", "--package", "moonlight-bridge-codegen", "--",
        "lock", descriptorFile.get().asFile.absolutePath,
        rootProject.file("proto/schema.lock").absolutePath, "--check",
    )
}

tasks.register<Exec>("updateSchemaLock") {
    group = "build setup"
    description = "Accepts the current Protobuf schema as the new compatibility baseline"
    dependsOn(generateProto)
    inputs.file(descriptorFile)
    outputs.file(rootProject.file("proto/schema.lock"))
    workingDir(rootProject.projectDir)
    commandLine(
        "cargo", "run", "--quiet", "--package", "moonlight-bridge-codegen", "--",
        "lock", descriptorFile.get().asFile.absolutePath,
        rootProject.file("proto/schema.lock").absolutePath, "--update",
    )
}

sourceSets.main {
    java.srcDir(generatedProtoDirectory)
    java.srcDir(generatedServiceDirectory)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateProto, generateMoonLightServices)
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("typedIntegrationTest") {
    group = "verification"
    description = "Runs a generated Protobuf request against the typed Rust service"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.example.v1.TypedIntegrationMain"
}
