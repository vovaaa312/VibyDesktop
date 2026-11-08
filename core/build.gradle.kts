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
    // process priority: downloads below normal, playback above (ProcessPriority)
    implementation(libs.jna)

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

tasks.register<JavaExec>("devPriority") {
    group = "verification"
    description = "Checks queue priority (job and track drags) against real YouTube in build/dev-home."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.core.DevPriority"
    systemProperty("viby.home", layout.buildDirectory.dir("dev-home").get().asFile.absolutePath)
}

tasks.register<JavaExec>("devQueue") {
    group = "verification"
    description = "Runs one URL through the real download queue in build/dev-home."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.core.DevQueue"
    systemProperty("viby.home", layout.buildDirectory.dir("dev-home").get().asFile.absolutePath)
}

// Bundles the Desktop OAuth client (client_secret*.json in the project root, never committed).
// Builds without it simply hide Google sign-in.
tasks.processResources {
    from(rootProject.projectDir) {
        include("client_secret*.json")
        rename { "google_oauth.json" }
    }
}

tasks.register<JavaExec>("devLyrics") {
    group = "verification"
    description = "Fetches lyrics for the given video ids from live YouTube."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.core.DevLyrics"
    jvmArgs("-Dstdout.encoding=UTF-8")
}

tasks.register<JavaExec>("devAccount") {
    group = "verification"
    description = "Checks the stored Google sign-in of the real app directory (read-only)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.viby.core.DevAccount"
}
