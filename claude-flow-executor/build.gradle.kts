plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    implementation(project(":claude-flow-core"))

    // JSON Serialization - Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")

    // Process execution
    implementation("org.zeroturnaround:zt-exec:1.12")
}
