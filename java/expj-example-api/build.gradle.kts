plugins {
    `java-library`
}

group = "dev.expj"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":java:expj-client"))
    api("com.google.protobuf:protobuf-java:4.36.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val generatedProtoDirectory = layout.buildDirectory.dir("generated/sources/proto/main/java")
val generatedServiceDirectory = layout.buildDirectory.dir("generated/sources/expj/main/java")
val descriptorFile = layout.buildDirectory.file("generated/descriptors/example.pb")

val generateProto = tasks.register<Exec>("generateProto") {
    val schema = rootProject.file("proto/expj/example/v1/echo.proto")
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

val generateExpjServices = tasks.register<Exec>("generateExpjServices") {
    dependsOn("checkSchemaCompatibility")
    inputs.file(descriptorFile)
    inputs.files(rootProject.fileTree("crates/expj-codegen"))
    outputs.dir(generatedServiceDirectory)
    doFirst { generatedServiceDirectory.get().asFile.mkdirs() }
    workingDir(rootProject.projectDir)
    commandLine(
        "cargo", "run", "--quiet", "--package", "expj-codegen", "--",
        descriptorFile.get().asFile.absolutePath,
        generatedServiceDirectory.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("checkSchemaCompatibility") {
    dependsOn(generateProto)
    inputs.file(descriptorFile)
    inputs.file(rootProject.file("proto/schema.lock"))
    inputs.files(rootProject.fileTree("crates/expj-codegen"))
    workingDir(rootProject.projectDir)
    commandLine(
        "cargo", "run", "--quiet", "--package", "expj-codegen", "--",
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
        "cargo", "run", "--quiet", "--package", "expj-codegen", "--",
        "lock", descriptorFile.get().asFile.absolutePath,
        rootProject.file("proto/schema.lock").absolutePath, "--update",
    )
}

sourceSets.main {
    java.srcDir(generatedProtoDirectory)
    java.srcDir(generatedServiceDirectory)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateProto, generateExpjServices)
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("typedIntegrationTest") {
    group = "verification"
    description = "Runs a generated Protobuf request against the typed Rust service"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.expj.example.v1.TypedIntegrationMain"
}
