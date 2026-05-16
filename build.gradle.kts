import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "com.micyou.plugin"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    compileOnly(files("../MicYou/plugin-api/build/libs/plugin-api-jvm-1.0.0.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.jar {
    archiveFileName.set("plugin.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Copy>("copyDependencies") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("libs/lib"))
}

tasks.register<Zip>("packagePlugin") {
    group = "build"
    description = "Packages the plugin into a .micyou-plugin.zip file"

    dependsOn(tasks.jar)
    dependsOn("copyDependencies")

    archiveFileName.set("ios2pc-myp-v2.micyou-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from("src/main/resources/plugin.json")

    from(tasks.jar.get().outputs.files) {
        rename { "plugin.jar" }
    }

    from(layout.buildDirectory.dir("libs/lib")) {
        into("lib")
    }

    from("src/main/resources") {
        include("icon.png")
    }
}
