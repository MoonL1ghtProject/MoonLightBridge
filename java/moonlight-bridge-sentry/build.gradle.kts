plugins {
    `java-library`
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
    api(project(":java:moonlight-bridge-client"))
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

val traceSampleRate = telemetryRate("moonlightBridge.internal.telemetry.traceSampleRate", "0.001")
val profileSampleRate = telemetryRate("moonlightBridge.internal.telemetry.profileSampleRate", "0.0")
val successLogs = providers.gradleProperty("moonlightBridge.internal.telemetry.successLogs").getOrElse("false")
val sentryDebug = providers.gradleProperty("moonlightBridge.internal.telemetry.debug").getOrElse("false")
require(successLogs == "true" || successLogs == "false") {
    "moonlightBridge.internal.telemetry.successLogs must be true or false"
}
require(sentryDebug == "true" || sentryDebug == "false") {
    "moonlightBridge.internal.telemetry.debug must be true or false"
}

tasks.processResources {
    inputs.properties(
        "traceSampleRate" to traceSampleRate,
        "profileSampleRate" to profileSampleRate,
        "successLogs" to successLogs,
        "sentryDebug" to sentryDebug,
        "telemetryRelease" to project.version.toString()
    )
    filesMatching("META-INF/moonlight-bridge/telemetry.properties") {
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
    mainClass = "ru.moonlightproject.bridge.sentry.AutomaticTelemetryMain"
}
