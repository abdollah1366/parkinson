plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.example.parkinson"
version = "0.1.0"

// Builds Java 21 bytecode with whatever JDK runs Gradle (21 or newer), so a newer Java on a test PC still works.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("com.example.parkinson.server.ApplicationKt")
}

val ktor = "3.2.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-rate-limit:$ktor")
    implementation("io.ktor:ktor-server-default-headers:$ktor")
    implementation("io.ktor:ktor-server-forwarded-header:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")

    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.0")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    // A PostgreSQL started by the server itself (EMBEDDED_DB_DIR, run-local.bat): testing on a PC with nothing to install.
    implementation("io.zonky.test:embedded-postgres:2.1.0") {
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-darwin-amd64")
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64-alpine")
    }

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

tasks.test {
    useJUnitPlatform()
    // TEST_DATABASE_URL points the tests at an existing PostgreSQL; without it they start one in Docker.
    environment("TEST_DATABASE_URL", System.getenv("TEST_DATABASE_URL") ?: "")
}
