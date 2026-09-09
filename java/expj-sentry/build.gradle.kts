plugins {
    `java-library`
}

group = "dev.expj"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

dependencies {
    api(project(":java:expj-client"))
    implementation("io.sentry:sentry:8.55.0")
    runtimeOnly("io.sentry:sentry-async-profiler:8.55.0")
}

fun telemetryRate(property: String, fallback: String): String {
    val value = providers.gradleProperty(property).getOrElse(fallback)
    val parsed = value.toDoubleOrNull()
    require(parsed != null && parsed in 0.0..1.0) {
        "$property must be a number from 0.0 to 1.0"
    }
    return value
}

val traceSampleRate = telemetryRate("expj.internal.telemetry.traceSampleRate", "0.001")
val profileSampleRate = telemetryRate("expj.internal.telemetry.profileSampleRate", "0.0")
val successLogs = providers.gradleProperty("expj.internal.telemetry.successLogs").getOrElse("false")
val sentryDebug = providers.gradleProperty("expj.internal.telemetry.debug").getOrElse("false")
require(successLogs == "true" || successLogs == "false") {
    "expj.internal.telemetry.successLogs must be true or false"
}
require(sentryDebug == "true" || sentryDebug == "false") {
    "expj.internal.telemetry.debug must be true or false"
}

tasks.processResources {
    inputs.properties(
        "traceSampleRate" to traceSampleRate,
        "profileSampleRate" to profileSampleRate,
        "successLogs" to successLogs,
        "sentryDebug" to sentryDebug,
        "telemetryRelease" to project.version.toString()
    )
    filesMatching("META-INF/expj/telemetry.properties") {
        expand(
            "traceSampleRate" to traceSampleRate,
            "profileSampleRate" to profileSampleRate,
            "successLogs" to successLogs,
            "sentryDebug" to sentryDebug,
            "telemetryRelease" to project.version.toString()
        )
    }
}

tasks.register<JavaExec>("automaticTelemetryTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.expj.sentry.AutomaticTelemetryMain"
}
