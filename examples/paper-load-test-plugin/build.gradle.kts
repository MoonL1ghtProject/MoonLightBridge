import org.gradle.api.file.DuplicatesStrategy

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    // Published code generator; the version comes from publishedMoonLightBridgeVersion.
    id("ru.moonlightproject.bridge")
}

val publishedMoonLightBridgeVersion: String by project

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation("ru.moonlightproject:moonlight-bridge-paper:$publishedMoonLightBridgeVersion")
    implementation("com.google.protobuf:protobuf-java:4.36.1")
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.processResources {
    inputs.property("pluginVersion", project.version.toString())

    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val verifyPluginDescriptorVersion = tasks.register("verifyPluginDescriptorVersion") {
    group = "verification"
    dependsOn(tasks.processResources)

    doLast {
        val descriptor = layout.buildDirectory.file("resources/main/plugin.yml").get().asFile
        val expectedVersion = "version: '${project.version}'"
        check(descriptor.readLines().contains(expectedVersion)) {
            "Expected $expectedVersion in ${descriptor.path}"
        }
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("com.google.protobuf", "ru.moonlightproject.bridge.load.internal.protobuf")
    relocate("io.sentry", "ru.moonlightproject.bridge.load.internal.sentry")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.check {
    dependsOn(verifyPluginDescriptorVersion)
}

// The Echo client is generated from examples/contract by the released generator.
moonlightBridge {
    protoDirectory.set(rootProject.layout.projectDirectory.dir("examples/contract/proto"))
    schemaLock.set(rootProject.layout.projectDirectory.file("examples/contract/schema.lock"))
    codegenExecutable.set(
        rootProject.layout.buildDirectory
            .file("published-codegen/$publishedMoonLightBridgeVersion/bin/moonlight-bridge-codegen")
            .get().asFile.absolutePath
    )
}

tasks.matching { it.name == "generateMoonLightBridge" || it.name == "checkMoonLightSchema" }.configureEach {
    dependsOn(rootProject.tasks.named("installPublishedCodegen"))
}
