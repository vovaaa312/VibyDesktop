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
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.viby.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Viby"
            packageVersion = project.version.toString()
            description = "YouTube music downloader and player"
            vendor = "vovaaa312"
            modules("java.net.http", "java.sql", "java.desktop", "jdk.crypto.ec", "jdk.httpserver")

            windows {
                menuGroup = "Viby"
                upgradeUuid = "8f1d4c63-2a7e-4b0e-9c8e-5d9a3f6b7c21"
            }
            macOS {
                bundleID = "com.viby.desktop"
            }
        }
    }
}
