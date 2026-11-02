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
