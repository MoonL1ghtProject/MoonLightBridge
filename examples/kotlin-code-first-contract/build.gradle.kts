plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":java:moonlight-bridge-code-first"))
    ksp(project(":java:moonlight-bridge-code-first"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

val contractSchema = layout.buildDirectory.file("generated/kotlin/contract.proto")
val contractLock = layout.projectDirectory.file("schema.lock")
val rustSource = layout.projectDirectory.file("src/main/rust/contract.rs")
val rustSchema = layout.buildDirectory.file("generated/rust/contract.proto")

ksp {
    arg("moonlight.output", contractSchema.get().asFile.absolutePath)
    arg("moonlight.schemaLock", contractLock.asFile.absolutePath)
}

tasks.configureEach {
    if (name == "kspKotlin") {
        inputs.file(contractLock)
        outputs.file(contractSchema)
    }
}

val buildRustCodegen = tasks.register<Exec>("buildRustCodegen") {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--package", "moonlight-bridge-codegen")
}

val generateRustSchema = tasks.register<Exec>("generateRustSchema") {
    dependsOn(buildRustCodegen)
    inputs.files(rustSource, contractLock)
    outputs.file(rustSchema)
    doFirst { rustSchema.get().asFile.parentFile.mkdirs() }
    commandLine(
        rootProject.file("target/debug/moonlight-bridge-codegen").absolutePath,
        "source", "rust",
        rustSource.asFile.absolutePath,
        rustSchema.get().asFile.absolutePath,
        contractLock.asFile.absolutePath,
    )
}

val compileRustDescriptor = tasks.register<Exec>("compileRustDescriptor") {
    dependsOn(generateRustSchema)
    val descriptor = layout.buildDirectory.file("generated/rust/contract.pb")
    inputs.file(rustSchema)
    outputs.file(descriptor)
    commandLine(
        "protoc",
        "--proto_path=${rustSchema.get().asFile.parentFile}",
        "--proto_path=${rootProject.file("proto")}",
        "--include_imports",
        "--descriptor_set_out=${descriptor.get().asFile}",
        rustSchema.get().asFile.absolutePath,
    )
}

val verifyKotlinContract = tasks.register<Exec>("verifyKotlinContract") {
    dependsOn(tasks.named("kspKotlin"))
    dependsOn(compileRustDescriptor)
    inputs.file(contractSchema)
    val descriptor = layout.buildDirectory.file("generated/kotlin/contract.pb")
    outputs.file(descriptor)
    doFirst {
        contractSchema.get().asFile.parentFile.mkdirs()
        val schema = contractSchema.get().asFile.readText()
        check("string message = 8;" in schema) { schema }
        check("READY = 4;" in schema) { schema }
        check("reserved 7;" in schema && "reserved \"LEGACY\";" in schema) { schema }
    }
    commandLine(
        "protoc",
        "--proto_path=${contractSchema.get().asFile.parentFile}",
        "--proto_path=${rootProject.file("proto")}",
        "--include_imports",
        "--descriptor_set_out=${descriptor.get().asFile}",
        contractSchema.get().asFile.absolutePath,
    )
    doLast {
        val rustDescriptor = layout.buildDirectory.file("generated/rust/contract.pb").get().asFile
        check(descriptor.get().asFile.readBytes().contentEquals(rustDescriptor.readBytes())) {
            "Kotlin and Rust code-first descriptors differ"
        }
    }
}

tasks.named("check") { dependsOn(verifyKotlinContract) }
