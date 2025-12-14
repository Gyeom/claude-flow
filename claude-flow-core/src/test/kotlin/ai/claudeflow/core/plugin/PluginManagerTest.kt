package ai.claudeflow.core.plugin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PluginManagerTest : BehaviorSpec({

    Given("PluginManager가 초기화되었을 때") {
        val pluginManager = PluginManager()

        Then("플러그인이 없어야 한다") {
            pluginManager.getAll().size shouldBe 0
        }
    }

    Given("테스트 플러그인을 등록할 때") {
        val pluginManager = PluginManager()
        val testPlugin = TestPlugin()

        When("register를 호출하면") {
            pluginManager.register(testPlugin, mapOf("key" to "value"))

            Then("플러그인이 등록되어야 한다") {
                pluginManager.getAll().size shouldBe 1
            }

            Then("플러그인을 조회할 수 있어야 한다") {
                pluginManager.get("test") shouldNotBe null
                pluginManager.get("test")?.name shouldBe "Test Plugin"
            }
        }
    }

    Given("플러그인 초기화 테스트") {
        val pluginManager = PluginManager()
        val testPlugin = TestPlugin()
        pluginManager.register(testPlugin, mapOf("TEST_KEY" to "test_value"))

        When("initialize를 호출하면") {
            kotlinx.coroutines.runBlocking {
                pluginManager.initialize("test")
            }

            Then("플러그인이 활성화되어야 한다") {
                pluginManager.get("test")?.enabled shouldBe true
            }
        }
    }

    Given("플러그인 명령어 실행 테스트") {
        val pluginManager = PluginManager()
        val testPlugin = TestPlugin()
        pluginManager.register(testPlugin, mapOf("TEST_KEY" to "test_value"))

        kotlinx.coroutines.runBlocking {
            pluginManager.initialize("test")
        }

        When("execute를 호출하면") {
            val result = kotlinx.coroutines.runBlocking {
                pluginManager.execute("test", "echo", mapOf("message" to "Hello"))
            }

            Then("명령이 실행되어야 한다") {
                result.success shouldBe true
                result.data shouldBe "Hello"
            }
        }

        When("존재하지 않는 명령어를 실행하면") {
            val result = kotlinx.coroutines.runBlocking {
                pluginManager.execute("test", "unknown", emptyMap())
            }

            Then("에러가 반환되어야 한다") {
                result.success shouldBe false
                result.error shouldNotBe null
            }
        }
    }

    Given("플러그인 제거 테스트") {
        val pluginManager = PluginManager()
        val testPlugin = TestPlugin()
        pluginManager.register(testPlugin, mapOf("TEST_KEY" to "test_value"))

        kotlinx.coroutines.runBlocking {
            pluginManager.initialize("test")
        }

        When("unregister를 호출하면") {
            kotlinx.coroutines.runBlocking {
                pluginManager.unregister("test")
            }

            Then("플러그인이 제거되어야 한다") {
                pluginManager.get("test") shouldBe null
            }
        }
    }

    Given("findHandler 테스트") {
        val pluginManager = PluginManager()
        val testPlugin = TestPlugin()
        pluginManager.register(testPlugin, mapOf("TEST_KEY" to "test_value"))

        kotlinx.coroutines.runBlocking {
            pluginManager.initialize("test")
        }

        When("매칭되는 메시지로 findHandler를 호출하면") {
            val handler = pluginManager.findHandler("/test echo hello")

            Then("핸들러가 반환되어야 한다") {
                handler shouldNotBe null
                handler?.id shouldBe "test"
            }
        }

        When("매칭되지 않는 메시지로 findHandler를 호출하면") {
            val handler = pluginManager.findHandler("random message")

            Then("null이 반환되어야 한다") {
                handler shouldBe null
            }
        }
    }
})

/**
 * 테스트용 플러그인
 */
class TestPlugin : BasePlugin() {
    override val id = "test"
    override val name = "Test Plugin"
    override val description = "Plugin for testing"

    override val commands = listOf(
        PluginCommand(
            name = "echo",
            description = "Echo a message",
            usage = "/test echo <message>",
            examples = listOf("/test echo hello")
        )
    )

    override fun shouldHandle(message: String): Boolean {
        return message.lowercase().startsWith("/test")
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            "echo" -> PluginResult(
                success = true,
                data = args["message"] as? String ?: "No message"
            )
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }
}
