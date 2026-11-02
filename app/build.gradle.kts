import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.coroutines.swing)
    implementation(libs.jnativehook)
    implementation(libs.jna)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.viby.app.MainKt"
        // local packaging needs a JDK with jpackage: ./gradlew packageMsi -PpackagingJavaHome=<jdk>
        providers.gradleProperty("packagingJavaHome").orNull?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Viby"
            packageVersion = project.version.toString()
            description = "YouTube music downloader and player"
            vendor = "vovaaa312"
            modules("java.instrument", "java.net.http", "java.sql", "java.desktop", "jdk.crypto.ec",
                "jdk.httpserver", "jdk.unsupported")

            windows {
                menuGroup = "Viby"
                shortcut = true
                menu = true
                dirChooser = true
                upgradeUuid = "8f1d4c63-2a7e-4b0e-9c8e-5d9a3f6b7c21"
                iconFile = project.file("icons/viby.ico")
            }
            macOS {
                bundleID = "com.viby.desktop"
                dockName = "Viby"
                iconFile = project.file("icons/viby.icns")
            }
            linux {
                iconFile = project.file("icons/viby.png")
            }
        }
    }
}

// `run` loads classes lazily straight from the build output, so a rebuild while the app is open
// swaps them under the running JVM (NoClassDefFoundError on the next new class). Launch from a
// private copy of the classpath instead; copies of earlier runs are removed once unlocked.
// Compose registers `run` after evaluation, hence the lazy lookup
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val snapshots = layout.buildDirectory.dir("run-classpath").get().asFile
    val noClasspath = files()
    doFirst {
        // Windows refuses to rename a folder whose jars a running instance holds open
        snapshots.listFiles()?.forEach { old ->
            val doomed = File(snapshots, "${old.name}.old")
            if (old.renameTo(doomed)) doomed.deleteRecursively()
        }
        val target = File(snapshots, System.currentTimeMillis().toString())
        val entries = classpath.files.filter { it.exists() }.mapIndexed { index, source ->
            val copy = File(target, "$index-${source.name}")
            source.copyRecursively(copy, overwrite = true)
            copy.absolutePath
        }
        val argFile = File(target, "classpath.args")
        argFile.writeText("-cp \"" + entries.joinToString(File.pathSeparator).replace("\\", "\\\\") + "\"")
        classpath = noClasspath
        jvmArgs("@${argFile.absolutePath}")
    }
}

tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "Downloads a video through the real engine and renders the screens into build/screens."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.app.RenderScreensKt"
    args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
    providers.gradleProperty("url").orNull?.let { args(it) }
    // reuses the yt-dlp/ffmpeg/deno installed by :core:devDownload
    systemProperty("viby.home", rootProject.file("core/build/dev-home").absolutePath)
}

tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    filesMatching("viby-version.txt") { expand("version" to appVersion) }
}


tasks.register<JavaExec>("devMediaKeys") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.app.DevMediaKeysKt"
}

tasks.register<JavaExec>("renderLayouts") {
    group = "verification"
    description = "Renders the main layout at several window sizes into build/screens."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.app.RenderLayoutsKt"
    args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
    systemProperty("viby.home", rootProject.file("core/build/dev-home").absolutePath)
}
