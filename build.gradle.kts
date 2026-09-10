import org.gradle.api.tasks.bundling.Jar

plugins {
    base
}

allprojects {
    group = "ru.moonlightproject"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
                "Implementation-Title" to "MoonLightBridge",
                "Implementation-Vendor" to "~VicTim~",
                "Implementation-URL" to "https://dev.moonlightproject.ru"
            )
        }
    }
}
