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

tasks.register<JavaExec>("integrationTest") {
    group = "verification"
    description = "Runs the Java client against the example Rust backend"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.client.IntegrationMain"
    when (providers.gradleProperty("moonlightBridgeTransport").orNull) {
        "unix" -> args("unix", providers.gradleProperty("moonlightBridgeSocketPath").get())
        "tls" -> {
            args("tls")
            systemProperty("javax.net.ssl.keyStore", providers.gradleProperty("moonlightBridgeKeyStore").get())
            systemProperty("javax.net.ssl.keyStorePassword", "changeit")
            systemProperty("javax.net.ssl.trustStore", providers.gradleProperty("moonlightBridgeTrustStore").get())
            systemProperty("javax.net.ssl.trustStorePassword", "changeit")
        }
        else -> args("tcp")
    }
}

tasks.register<JavaExec>("reconnectIntegrationTest") {
    group = "verification"
    description = "Verifies reconnect without replaying an interrupted request"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.client.ReconnectIntegrationMain"
    doFirst {
        args(providers.gradleProperty("moonlightBridgeMarkerDirectory").get())
    }
}

val nonBlockingStartTest = tasks.register<JavaExec>("nonBlockingStartTest") {
    group = "verification"
    description = "Verifies that supervised startup never blocks its caller"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.client.NonBlockingStartMain"
}

val protocolValidationTest = tasks.register<JavaExec>("protocolValidationTest") {
    group = "verification"
    description = "Rejects a response with a mismatched frame kind or method ID"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.client.ProtocolValidationMain"
}

tasks.check {
    dependsOn(nonBlockingStartTest, protocolValidationTest)
}

tasks.register<JavaExec>("performanceBenchmark") {
    group = "verification"
    description = "Measures MoonLightBridge end-to-end latency and pipelined throughput"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.client.BenchmarkMain"
    args(providers.gradleProperty("moonlightBridgeBenchmarkEndpoint").getOrElse("tcp://127.0.0.1:38191"))
}
