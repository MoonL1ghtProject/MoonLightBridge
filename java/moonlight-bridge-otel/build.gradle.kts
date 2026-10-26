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

// The tracer reports the framework version. A shaded plugin manifest would report the plugin's
// own version instead, so the version is compiled in.
val generateVersion = tasks.register("generateMoonLightVersion") {
    val output = layout.buildDirectory.dir("generated/sources/moonlightVersion/java")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("ru/moonlightproject/bridge/otel/MoonLightOtelVersion.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package ru.moonlightproject.bridge.otel;\n\n" +
                "final class MoonLightOtelVersion {\n" +
                "    static final String VERSION = \"" + version + "\";\n\n" +
                "    private MoonLightOtelVersion() { }\n" +
                "}\n"
        )
    }
}

sourceSets.main {
    java.srcDir(generateVersion)
}

val adapterTest = tasks.register<JavaExec>("adapterTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.otel.OpenTelemetryFixtureMain"
}

tasks.check { dependsOn(adapterTest) }
