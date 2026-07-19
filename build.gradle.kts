import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "com.basetool"
// The version comes from the release tag in CI: the workflow strips the leading `v`
// from e.g. `v1.2.0` and exports APP_VERSION, which the build reads here. Local builds
// (no APP_VERSION / no -PappVersion) fall back to the dev default below. Don't hardcode
// a release version — tag the commit instead.
version = (System.getenv("APP_VERSION") ?: findProperty("appVersion") as String?)
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
    ?: "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    // The `compose.material3` DSL accessor is deprecated; declare the dependency directly. 1.9.0 is
    // the STABLE Material3 release the Compose 1.11.1 plugin maps to (no longer alpha) — keep it in
    // step with the Compose plugin version when that bumps.
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    // Compose resources library — bundled images/SVGs via the generated `Res` + `painterResource`
    // (replaces the deprecated androidx.compose.ui.res.useResource/loadImageBitmap/loadSvgPainter).
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Classical-OCR digit cross-check (PP-OCRv3 recognition via ONNX Runtime) — a decorrelated
    // second reader for the numeric cells the VLM mis-reads (refinery-digit-misread-recovery).
    implementation("com.microsoft.onnxruntime:onnxruntime:1.27.0")

    testImplementation(kotlin("test"))
}

// Generate BuildInfo.VERSION from the project version (which CI sets from the release tag) so the
// app's reported version — shown in the GUI and written into the export `toolVersion` — always
// matches the MSI version, with no hand-edited constant to drift out of sync. Generated into
// build/ (gitignored),
// regenerated whenever the version changes.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionValue = project.version.toString()
    val outDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    inputs.property("version", versionValue)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("com/basetool/bpextractor/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.basetool.bpextractor
            |
            |/** Generated from the project version (the release tag in CI). Do not edit. */
            |internal object BuildInfo {
            |    const val VERSION: String = "$versionValue"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    jvmToolchain(25)
    sourceSets.named("main") {
        kotlin.srcDir(generateBuildInfo)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

// Bundled drawables live in src/main/composeResources/drawable and are reached through the
// generated `Res` class in this fixed package (so the imports below don't depend on the module
// coordinate). Always generate the class — this is a JVM (not multiplatform) project.
compose.resources {
    publicResClass = false
    packageOfResClass = "com.basetool.bpextractor.resources"
    generateResClass = always
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    // The CredentialStore round-trip test binds Advapi32.dll via the Foreign Function & Memory
    // API; grant native access so it does not warn (and is not blocked by a future JDK), matching
    // the packaged app's jvmArgs.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.basetool.bpextractor.MainKt"

        // jpackage builds the bundled runtime (via jlink) and the installer with THIS JDK.
        // Without it, the Compose plugin falls back to the Gradle daemon's JDK (21) — so the
        // app would ship a Java 21 runtime even though we compile to Java 25 bytecode, which
        // would crash at launch with UnsupportedClassVersionError. Pin it to the JDK 25
        // toolchain so the compiled bytecode and the bundled runtime stay in lockstep.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile.absolutePath

        // Skiko loads its native renderer via System.load(); on JDK 25 that prints
        // "restricted method / native access" warnings to stderr. Granting native access
        // to the (classpath = unnamed) module silences them and future-proofs against the
        // announced block in a later JDK.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            // Msi -> classic Windows installer that registers in "Apps & Features"
            // and is uninstallable like any normal program. (Requires WiX 7 at
            // build time — package-msi.ps1 sets that up; see README.)
            targetFormats(TargetFormat.Msi)

            // Rebranded for the multi-workflow app (epic #439 Phase 3); the unchanged
            // upgradeUuid below makes the renamed app REPLACE an installed
            // "Basetool Blueprint Extractor" in place instead of installing side by side.
            packageName = "Basetool SC Extractor"
            // MSI ProductVersion must be strictly numeric (major.minor.build), so drop
            // any pre-release suffix (e.g. -rc1) — jpackage/WiX would otherwise reject it.
            packageVersion = project.version.toString().substringBefore('-').ifBlank { "1.0.0" }
            description = "Extracts Star Citizen data (blueprints from Game.log, refinery orders from screenshots) into JSON."
            vendor = "Basetool"
            copyright = "© 2026 Basetool. GPL-3.0-or-later. Unofficial Star Citizen fan tool, not affiliated with the Cloud Imperium group of companies. Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC."

            // Bundle only the JDK modules the app actually needs instead of the whole
            // JDK — keeps the installer small. The Compose plugin auto-infers the base
            // set (java.base, java.desktop for AWT/Skiko, …); the extras come from
            // `gradlew suggestRuntimeModules` (jdeps): jdk.unsupported for Skiko's
            // sun.misc.Unsafe, java.instrument pulled in by the coroutines agent hooks,
            // java.net.http for the Ollama client and jdk.management for the
            // hardware-preflight RAM probe (OperatingSystemMXBean.getTotalMemorySize).
            modules("java.instrument", "jdk.unsupported", "java.net.http", "jdk.management")

            windows {
                // Stable identity so future versions upgrade the install in place
                // instead of leaving duplicates in Apps & Features.
                upgradeUuid = "530c8db7-fe35-4e3f-8ac4-1af9a611a0a6"

                menuGroup = "Basetool"
                menu = true          // Start-menu entry
                shortcut = true      // Desktop shortcut
                dirChooser = true    // let the user choose the install directory (install wizard step)
                perUserInstall = true // no admin elevation; shows under the user's "Apps & Features"

                // Optional custom icon. Drop an app.ico into src/main/resources to enable.
                val icon = project.file("src/main/resources/app.ico")
                if (icon.exists()) {
                    iconFile.set(icon)
                }
            }
        }
    }
}
