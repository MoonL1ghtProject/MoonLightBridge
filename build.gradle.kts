plugins {
    base
}

allprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
    }
}
