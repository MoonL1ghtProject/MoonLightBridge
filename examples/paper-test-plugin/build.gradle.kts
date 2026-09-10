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
    implementation(project(":java:moonlight-bridge-framework"))
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
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // Paper forks may expose their own, older protobuf-java through the parent
    // classloader. Keep MoonLightBridge's generated messages and runtime fully isolated.
    relocate("com.google.protobuf", "ru.moonlightproject.bridge.internal.protobuf")
    relocate("io.sentry", "ru.moonlightproject.bridge.internal.sentry")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.register<JavaExec>("backendSmokeTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.examples.paper.BackendSmokeMain"
    args(providers.gradleProperty("moonlightBridgeTestEndpoint").getOrElse("tcp://127.0.0.1:38201"))
}
