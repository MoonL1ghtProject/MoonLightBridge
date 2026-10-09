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
    api("io.opentelemetry:opentelemetry-api:1.66.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.66.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.66.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.66.0")
}

val adapterTest = tasks.register<JavaExec>("adapterTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.otel.OpenTelemetryFixtureMain"
}

tasks.check { dependsOn(adapterTest) }
