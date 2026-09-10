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
    api(project(":java:moonlight-bridge-paper"))
    runtimeOnly(project(":java:moonlight-bridge-sentry"))
}
