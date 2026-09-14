import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.Jar

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

val embeddedTelemetry = configurations.create("embeddedTelemetry") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Private implementation bundled into the public runtime"
}

dependencies {
    api(project(":java:moonlight-bridge-client"))
    embeddedTelemetry(project(":java:moonlight-bridge-sentry"))
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    configurations = listOf(embeddedTelemetry)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("io.sentry", "ru.moonlightproject.bridge.internal.telemetry.sdk")
    relocate("ru.moonlightproject.bridge.sentry", "ru.moonlightproject.bridge.internal.telemetry.provider")
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}

publishing.publications.withType<MavenPublication>().configureEach {
    artifacts.removeIf { it.classifier == null && it.extension == "jar" }
    artifact(tasks.named("shadowJar"))
}

// The Java component describes the intentionally unpublished thin JAR. Maven consumers receive
// the relocated runtime JAR and its POM instead, so publishing that component's Gradle metadata
// would point at an artifact that does not exist.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

val universalLifecycleTest = tasks.register<JavaExec>("universalLifecycleTest") {
    group = "verification"
    description = "Verifies the universal facade remains non-blocking and closes predictably"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ru.moonlightproject.bridge.UniversalLifecycleMain")
}

val embeddedRuntimeTest = tasks.register<JavaExec>("embeddedRuntimeTest") {
    group = "verification"
    description = "Verifies the relocated private runtime is discoverable from the published JAR"
    val shadowJar = tasks.named<ShadowJar>("shadowJar")
    val clientJar = project(":java:moonlight-bridge-client").tasks.named<Jar>("jar")
    dependsOn(tasks.testClasses, shadowJar, clientJar)
    classpath(
        sourceSets.test.get().output,
        shadowJar.flatMap { it.archiveFile },
        clientJar.flatMap { it.archiveFile },
    )
    mainClass.set("ru.moonlightproject.bridge.EmbeddedRuntimeMain")
}

tasks.check {
    dependsOn(universalLifecycleTest, embeddedRuntimeTest)
}
