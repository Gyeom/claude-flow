plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.serialization") version "2.1.20"
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":claude-flow-core"))
    implementation(project(":claude-flow-executor"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

    // Slack Bolt (Socket Mode)
    implementation("com.slack.api:bolt-socket-mode:1.46.0")
    implementation("com.slack.api:slack-api-client:1.46.0")
    // Java-WebSocket backend (more stable than Tyrus for long-running connections)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // HTTP Client for webhooks
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-cio:3.1.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}
