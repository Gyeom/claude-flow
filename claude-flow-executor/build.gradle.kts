plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    implementation(project(":claude-flow-core"))

    // JSON Serialization - Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")

    // 참고: zt-exec는 사용하지 않으므로 제거됨 (ProcessBuilder 사용 중)
}
