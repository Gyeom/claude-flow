plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Jackson for JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // Caffeine Cache (high-performance in-memory cache)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Jsoup for HTML parsing (Confluence)
    implementation("org.jsoup:jsoup:1.17.2")

    // Apache POI for Excel parsing (.xlsx)
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Retry with Exponential Backoff
    implementation("com.michael-bull.kotlin-retry:kotlin-retry:2.0.2")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Test dependencies
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
