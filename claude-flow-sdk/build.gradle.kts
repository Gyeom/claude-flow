plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
    `maven-publish`
}

dependencies {
    // Claude Flow Core
    implementation(project(":claude-flow-core"))

    // HTTP Client (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Test dependencies
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Maven Publishing Configuration
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "ai.claudeflow"
            artifactId = "claude-flow-sdk"
            version = "1.0.0"

            from(components["kotlin"])

            pom {
                name.set("Claude Flow SDK")
                description.set("Official Kotlin SDK for Claude Flow - AI Agent Orchestration Platform")
                url.set("https://github.com/42dot/claude-flow")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("42dot")
                        name.set("42dot AI Team")
                        email.set("ai@42dot.ai")
                    }
                }
            }
        }
    }
}
