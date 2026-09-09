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
    runtimeOnly(project(":java:expj-sentry"))
}
