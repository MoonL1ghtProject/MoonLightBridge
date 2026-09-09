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

tasks.register<JavaExec>("integrationTest") {
    group = "verification"
    description = "Runs the Java client against the example Rust backend"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.expj.client.IntegrationMain"
    if (providers.gradleProperty("expjTransport").orNull == "unix") {
        args("unix", providers.gradleProperty("expjSocketPath").get())
    } else if (providers.gradleProperty("expjTransport").orNull == "tls") {
        args("tls")
        systemProperty("javax.net.ssl.keyStore", providers.gradleProperty("expjKeyStore").get())
        systemProperty("javax.net.ssl.keyStorePassword", "changeit")
        systemProperty("javax.net.ssl.trustStore", providers.gradleProperty("expjTrustStore").get())
        systemProperty("javax.net.ssl.trustStorePassword", "changeit")
    } else {
        args("tcp")
    }
}

tasks.register<JavaExec>("reconnectIntegrationTest") {
    group = "verification"
    description = "Verifies reconnect without replaying an interrupted request"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.expj.client.ReconnectIntegrationMain"
    args(providers.gradleProperty("expjMarkerDirectory").get())
}

tasks.register<JavaExec>("performanceBenchmark") {
    group = "verification"
    description = "Measures EXPJ end-to-end latency and pipelined throughput"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.expj.client.BenchmarkMain"
    args(providers.gradleProperty("expjBenchmarkEndpoint").getOrElse("tcp://127.0.0.1:38191"))
}
