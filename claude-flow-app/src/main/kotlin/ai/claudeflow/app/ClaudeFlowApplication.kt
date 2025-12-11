package ai.claudeflow.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ai.claudeflow"])
class ClaudeFlowApplication

fun main(args: Array<String>) {
    runApplication<ClaudeFlowApplication>(*args)
}
