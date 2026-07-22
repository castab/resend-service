import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("org.graalvm.buildtools.native") version "0.10.6"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.castab"
version = "0.3.0-SNAPSHOT"

repositories { mavenCentral() }

val http4kVersion = "6.56.0.0"
dependencies {
    implementation(platform("org.http4k:http4k-bom:$http4kVersion"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-client-okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.sksamuel.hoplite:hoplite-core:2.9.0")
    implementation("com.sksamuel.hoplite:hoplite-hocon:2.9.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.jdbi:jdbi3-core:3.49.0")
    implementation("org.flywaydb:flyway-core:12.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.14.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.3")
    testImplementation("io.kotest:kotest-assertions-core:6.0.3")
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_25 } }
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
application { mainClass = "com.castab.resend.MainKt" }
tasks.test { useJUnitPlatform() }

graalvmNative {
    binaries.named("main") {
        imageName.set("resend-service")
        mainClass.set(application.mainClass)
        buildArgs.addAll(
            "--no-fallback",
            "--gc=serial",
            "-O3",
            "-R:MinHeapSize=16m",
            "-R:MaxHeapSize=128m",
            "-H:+ReportExceptionStackTraces",
        )
    }
    metadataRepository { enabled.set(true) }
}
