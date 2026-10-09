plugins {
    `java-gradle-plugin`
}

dependencies {
    compileOnly(gradleApi())
    testImplementation(gradleTestKit())
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

gradlePlugin {
    plugins {
        create("moonLightBridge") {
            id = "ru.moonlightproject.bridge"
            implementationClass = "ru.moonlightproject.bridge.gradle.MoonLightBridgePlugin"
            displayName = "MoonLightBridge"
            description = "Generates and compatibility-checks MoonLightBridge RPC bindings"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.processResources {
    from(rootProject.file("proto/moonlight/bridge/options/v1/options.proto")) {
        into("moonlight-bridge-proto/moonlight/bridge/options/v1")
    }
}

tasks.register<JavaExec>("protoOnlyFunctionalTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.gradle.ProtoOnlyFunctionalMain"
}
