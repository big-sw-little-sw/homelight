plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
}

group = "io.github.bigswlittlesw"
version = "1.0-SNAPSHOT"

val mainClassName = "io.github.bigswlittlesw.homelight.cli.HomeLightCommandKt"

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
    // clikt-core, not clikt: the full artifact adds Mordant, a second terminal layer beside JLine.
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("io/github/bigswlittlesw/homelight/version.properties") {
        expand("project" to mapOf("version" to version))
    }
    // The one copy of the user guide: the Help screen and `homelight guide` read it from the jar.
    from("docs/user-guide.md") { into("io/github/bigswlittlesw/homelight") }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = mainClassName
}

// `./gradlew nativeCompile` with a GraalVM 25 JDK as JAVA_HOME (or GRAALVM_HOME).
// Platform flags (static linking, libc, -march) come from NATIVE_IMAGE_OPTIONS; see ci/native/.
graalvmNative {
    binaries.named("main") {
        imageName = "homelight"
        mainClass = mainClassName
        buildArgs.addAll(
            "--no-fallback",
            "-H:+ReportExceptionStackTraces",
            // JLine's JNI provider calls System.load; silences the JDK restricted-method warning.
            "--enable-native-access=ALL-UNNAMED",
        )
    }
}
