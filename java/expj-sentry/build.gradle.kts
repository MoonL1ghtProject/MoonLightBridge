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

tasks.register<JavaExec>("automaticTelemetryTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.expj.sentry.AutomaticTelemetryMain"
}
