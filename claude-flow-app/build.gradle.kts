plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":claude-flow-core"))
    implementation(project(":claude-flow-executor"))
    implementation(project(":claude-flow-api"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

    // Configuration
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("claude-flow.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // 프로젝트 루트에서 실행되도록 설정
    workingDir = rootProject.projectDir

    // JVM 메모리 설정 (대용량 파일 처리용)
    jvmArgs = listOf("-Xmx2g", "-Xms512m")

    // docker-compose/.env 파일에서 환경 변수 자동 로드
    doFirst {
        val envFile = file("${rootProject.projectDir}/docker-compose/.env")
        if (envFile.exists()) {
            envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
                .forEach { line ->
                    val (key, value) = line.split("=", limit = 2)
                    if (System.getenv(key) == null) {
                        environment(key.trim(), value.trim())
                    }
                }
            println("✓ Loaded environment variables from docker-compose/.env")
        }
    }
}
