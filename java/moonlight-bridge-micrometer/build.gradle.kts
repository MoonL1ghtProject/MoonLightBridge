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
    api("io.micrometer:micrometer-core:1.17.1")
}

val adapterTest = tasks.register<JavaExec>("adapterTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.micrometer.MicrometerFixtureMain"
}

tasks.check { dependsOn(adapterTest) }
