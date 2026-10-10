plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
}

group = "io.github.bigswlittlesw"
// .github/workflows/release.yml passes -PreleaseVersion=<tag without the v>. Every other build is a SNAPSHOT.
version = providers.gradleProperty("releaseVersion").getOrElse("1.0-SNAPSHOT")

val mainClassName = "io.github.bigswlittlesw.lighten.cli.LightenCommand"

val picocliCodegen = configurations.create("picocliCodegen")

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    // The Kotlin plugin adds kotlin-stdlib. Never add kotlin-reflect: Native Image would need its metadata.
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.toolkit)
    implementation(libs.tamboui.jline3.backend)
    // Renders the embedded user guide on the Help screen.
    implementation(libs.tamboui.toolkit.markdown)
    implementation(libs.picocli)
    implementation(libs.kotlinx.serialization.json)
    picocliCodegen(libs.picocli.codegen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Native Image needs reflection metadata for the picocli command classes. picocli's annotation processor
// cannot see Kotlin sources, so picocli-codegen generates the metadata from the compiled classes on every
// build, at the path the processor used. This avoids kapt.
val generatePicocliMetadata = tasks.register<JavaExec>("generatePicocliMetadata") {
    description = "Generates the picocli reflection metadata for Native Image."
    val outputDir = layout.buildDirectory.dir("generated/picocli-metadata")
    val outputFile = outputDir.get()
        .file("META-INF/native-image/picocli-generated/${project.group}/${project.name}/reflect-config.json").asFile
    classpath(picocliCodegen, sourceSets.main.map { it.output.classesDirs }, configurations.runtimeClasspath)
    mainClass = "picocli.codegen.aot.graalvm.ReflectionConfigGenerator"
    args("--output", outputFile.path, mainClassName)
    outputs.dir(outputDir)
    doFirst { outputFile.parentFile.mkdirs() }
}

sourceSets.main {
    resources.srcDir(generatePicocliMetadata)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("io/github/bigswlittlesw/lighten/version.properties") {
        expand("project" to mapOf("version" to version))
    }
    // The one copy of the user guide: the Help screen and `lighten guide` read it from the jar.
    from("docs/user-guide.md") { into("io/github/bigswlittlesw/lighten") }
}

tasks.test {
    useJUnitPlatform()
    // Tests never see the developer's home: HOME and user.home both name an empty directory. TestHome.kt reads it.
    val home = layout.buildDirectory.dir("test-home").get().asFile
    environment("HOME", home.absolutePath)
    systemProperty("user.home", home.absolutePath)
    doFirst {
        home.deleteRecursively()
        home.mkdirs()
    }
}

application {
    mainClass = mainClassName
}

// Run `./gradlew nativeCompile` with a GraalVM 25 JDK as JAVA_HOME (or GRAALVM_HOME).
// The platform flags (static linking, libc, -march) come from NATIVE_IMAGE_OPTIONS, which
// ci/native/build-in-container.sh sets for each release platform.
graalvmNative {
    binaries.named("main") {
        imageName = "lighten"
        mainClass = mainClassName
        buildArgs.addAll(
            "--no-fallback",
            "-H:+ReportExceptionStackTraces",
            // JLine's JNI provider calls System.load. This flag stops the JDK's restricted-method warning.
            "--enable-native-access=ALL-UNNAMED",
        )
    }
}
