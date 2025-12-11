plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    implementation(project(":claude-flow-core"))

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Process execution
    implementation("org.zeroturnaround:zt-exec:1.12")
}
