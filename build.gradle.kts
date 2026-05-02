plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

repositories {
    mavenCentral()
}

val pluginApiPath: String by lazy {
    val envPath = System.getenv("MICYOU_PLUGIN_API_PATH")
    if (envPath != null && java.io.File(envPath).exists()) {
        envPath
    } else {
        val localPath = "../MicYou/plugin-api/build/libs/plugin-api-jvm.jar"
        if (java.io.File(localPath).exists()) {
            localPath
        } else {
            // Fallback for absolute path from original project
            "/home/ctyun/MicYou/plugin-api/build/libs/plugin-api-jvm.jar"
        }
    }
}

dependencies {
    compileOnly(files(pluginApiPath))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

kotlin {
    jvmToolchain(11)
}

tasks.jar {
    archiveBaseName.set("ios2pc")
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
