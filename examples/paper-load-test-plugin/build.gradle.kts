import org.gradle.api.file.DuplicatesStrategy

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(project(":java:moonlight-bridge-paper"))
    implementation(project(":java:moonlight-bridge-example-api"))
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
