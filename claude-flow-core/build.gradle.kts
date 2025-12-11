plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
}
