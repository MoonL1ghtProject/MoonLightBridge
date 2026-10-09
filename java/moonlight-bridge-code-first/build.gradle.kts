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

tasks.register<JavaExec>("processorTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.codegen.CodeFirstProcessorMain"
    systemProperty("moonlight.repositoryRoot", rootProject.projectDir.absolutePath)
}

val buildRustCodegen = tasks.register<Exec>("buildRustCodegen") {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--package", "moonlight-bridge-codegen")
}

tasks.register<JavaExec>("crossFrontendTest") {
    group = "verification"
    dependsOn(tasks.testClasses, buildRustCodegen)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ru.moonlightproject.bridge.codegen.CrossFrontendMain"
    systemProperty("moonlight.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty(
        "moonlight.codegenExecutable",
        rootProject.file("target/debug/moonlight-bridge-codegen").absolutePath,
    )
}
