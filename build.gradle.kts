import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.bundling.Jar

plugins {
    base
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = rootProject.group
    version = rootProject.version

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

val publishedJavaProjects = mapOf(
    ":java:moonlight-bridge-client" to "Core asynchronous Java client and transport runtime",
    ":java:moonlight-bridge-paper" to "Paper and Folia integration for MoonLightBridge",
    ":java:moonlight-bridge-sentry" to "Automatic Sentry telemetry provider for MoonLightBridge",
    ":java:moonlight-bridge-framework" to "Complete MoonLightBridge library for Minecraft plugins",
    ":java:moonlight-bridge-gradle-plugin" to "Gradle schema and code-generation plugin for MoonLightBridge",
)

configure(subprojects.filter { it.path in publishedJavaProjects }) {
    apply(plugin = "com.vanniktech.maven.publish")

    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()

        pom {
            name.set(project.name)
            description.set(publishedJavaProjects.getValue(project.path))
            inceptionYear.set("2026")
            url.set("https://github.com/MoonL1ghtProject/MoonLightBridge")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("1VicTim1")
                    name.set("~VicTim~")
                    email.set("122aaa121@gmail.com")
                    organization.set("MoonLightProject")
                    organizationUrl.set("https://dev.moonlightproject.ru")
                }
            }
            scm {
                url.set("https://github.com/MoonL1ghtProject/MoonLightBridge")
                connection.set("scm:git:https://github.com/MoonL1ghtProject/MoonLightBridge.git")
                developerConnection.set("scm:git:ssh://git@github.com/MoonL1ghtProject/MoonLightBridge.git")
            }
            issueManagement {
                system.set("GitHub Issues")
                url.set("https://github.com/MoonL1ghtProject/MoonLightBridge/issues")
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/moonl1ghtproject/moonlightbridge")
                credentials {
                    username = providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .getOrElse("")
                    password = providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .getOrElse("")
                }
            }
        }
    }

    tasks.withType<Jar>().configureEach {
        manifest.attributes("Implementation-Version" to project.version)
    }
}

tasks.register<Exec>("verifyReleaseVersion") {
    group = "verification"
    description = "Checks that Gradle and Cargo use the same non-SNAPSHOT release version"
    commandLine("bash", "scripts/check-release-version.sh", project.version.toString())
}

tasks.register("prepareJavaRelease") {
    group = "publishing"
    description = "Builds all public Java artifacts and their publication metadata without uploading"
    dependsOn("verifyReleaseVersion")
    dependsOn(publishedJavaProjects.keys.map { "$it:assemble" })
    dependsOn(publishedJavaProjects.keys.map { "$it:plainJavadocJar" })
    dependsOn(
        publishedJavaProjects.keys
            .filterNot { it.endsWith("moonlight-bridge-gradle-plugin") }
            .flatMap {
                listOf(
                    "$it:generatePomFileForMavenPublication",
                    "$it:generateMetadataFileForMavenPublication",
                )
            }
    )
    dependsOn(
        ":java:moonlight-bridge-gradle-plugin:generatePomFileForPluginMavenPublication",
        ":java:moonlight-bridge-gradle-plugin:generatePomFileForMoonLightBridgePluginMarkerMavenPublication",
        ":java:moonlight-bridge-gradle-plugin:generateMetadataFileForPluginMavenPublication",
        ":java:moonlight-bridge-gradle-plugin:generateMetadataFileForMoonLightBridgePluginMarkerMavenPublication",
    )
}

tasks.register("publishJavaToGitHubPackages") {
    group = "publishing"
    description = "Publishes all public Java artifacts to GitHub Packages"
    dependsOn("verifyReleaseVersion")
    dependsOn(publishedJavaProjects.keys.map { "$it:publishAllPublicationsToGitHubPackagesRepository" })
}

tasks.register("publishJavaToMavenCentral") {
    group = "publishing"
    description = "Publishes all public Java artifacts to the Maven Central Portal"
    dependsOn("verifyReleaseVersion")
    dependsOn(publishedJavaProjects.keys.map { "$it:publishAndReleaseToMavenCentral" })
}
