plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.graalvm.native)
}

group = "io.github.bigswlittlesw"
version = "1.0-SNAPSHOT"

val mainClassName = "io.github.bigswlittlesw.homelight.cli.HomeLightCommand"

val picocliCodegen = configurations.create("picocliCodegen")

repositories {
    mavenCentral()
    // TamboUI snapshots.
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    // The Kotlin plugin adds kotlin-stdlib. Never add kotlin-reflect: Native Image would need its metadata.
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.toolkit)
    implementation(libs.tamboui.jline3.backend)
    implementation(libs.picocli)
    implementation(libs.snakeyaml)
    implementation(libs.jackson.core)
    picocliCodegen(libs.picocli.codegen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Only the tests are Java.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Native Image needs reflection metadata for the picocli command classes. picocli's annotation processor
// cannot see Kotlin sources, and kapt cannot stub `@JvmRecord` classes, so picocli-codegen generates the
// metadata from the compiled classes on every build, at the path the processor used.
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
    filesMatching("io/github/bigswlittlesw/homelight/version.properties") {
        expand("project" to mapOf("version" to version))
    }
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
