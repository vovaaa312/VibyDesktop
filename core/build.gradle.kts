plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(libs.json)
    api(libs.jspecify)
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("devDownload") {
    group = "verification"
    description = "Installs yt-dlp/ffmpeg/deno into build/dev-home and downloads one video."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.core.DevDownload"
    systemProperty("viby.home", layout.buildDirectory.dir("dev-home").get().asFile.absolutePath)
}
