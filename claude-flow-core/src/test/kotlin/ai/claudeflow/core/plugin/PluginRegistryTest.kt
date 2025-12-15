package ai.claudeflow.core.plugin

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize

class PluginRegistryTest : DescribeSpec({

    describe("PluginRegistry") {

        describe("register and get") {
            it("should register and retrieve plugins") {
                val registry = PluginRegistry()
                val plugin = MockPlugin("test-1", "Test Plugin 1")
                val metadata = PluginMetadata(
                    id = "test-1",
                    name = "Test Plugin 1",
                    description = "A test plugin",
                    version = "1.0.0"
                )

                val success = registry.register(plugin, metadata)
                success shouldBe true

                val retrieved = registry.get("test-1")
                retrieved shouldNotBe null
                retrieved!!.name shouldBe "Test Plugin 1"
            }

            it("should prevent duplicate registration") {
                val registry = PluginRegistry()
                val plugin1 = MockPlugin("dup-1", "Plugin 1")
                val plugin2 = MockPlugin("dup-1", "Plugin 2")
                val metadata = PluginMetadata("dup-1", "Plugin", "", "1.0.0")

                registry.register(plugin1, metadata) shouldBe true
                registry.register(plugin2, metadata) shouldBe false
            }
        }

        describe("unregister") {
            it("should remove plugins") {
                val registry = PluginRegistry()
                val plugin = MockPlugin("remove-1", "Remove Test")
                val metadata = PluginMetadata("remove-1", "Remove Test", "", "1.0.0")

                registry.register(plugin, metadata)
                registry.contains("remove-1") shouldBe true

                registry.unregister("remove-1")
                registry.contains("remove-1") shouldBe false
            }
        }

        describe("getEnabled and getDisabled") {
            it("should filter by enabled status") {
                val registry = PluginRegistry()

                val enabledPlugin = MockPlugin("enabled-1", "Enabled")
                enabledPlugin.enabled = true
                val disabledPlugin = MockPlugin("disabled-1", "Disabled")
                disabledPlugin.enabled = false

                registry.register(enabledPlugin, PluginMetadata("enabled-1", "Enabled", "", "1.0.0"))
                registry.register(disabledPlugin, PluginMetadata("disabled-1", "Disabled", "", "1.0.0"))

                registry.getEnabled() shouldHaveSize 1
                registry.getDisabled() shouldHaveSize 1
            }
        }

        describe("findHandler") {
            it("should find plugin that can handle message") {
                val registry = PluginRegistry()

                val gitlabPlugin = object : MockPlugin("gitlab", "GitLab") {
                    override fun shouldHandle(message: String): Boolean {
                        return message.contains("MR") || message.contains("gitlab")
                    }
                }.apply { enabled = true }

                registry.register(gitlabPlugin, PluginMetadata("gitlab", "GitLab", "", "1.0.0"))

                val handler = registry.findHandler("리뷰해줘 MR !123")
                handler shouldNotBe null
                handler!!.id shouldBe "gitlab"
            }
        }

        describe("findByCommand") {
            it("should find plugin by command name") {
                val registry = PluginRegistry()

                val plugin = object : MockPlugin("jira", "Jira") {
                    override val commands = listOf(
                        PluginCommand("create-ticket", "Create a Jira ticket", "/jira create"),
                        PluginCommand("search", "Search Jira", "/jira search")
                    )
                }.apply { enabled = true }

                registry.register(plugin, PluginMetadata("jira", "Jira", "", "1.0.0"))

                val found = registry.findByCommand("create-ticket")
                found shouldNotBe null
                found!!.id shouldBe "jira"
            }
        }

        describe("getSummary") {
            it("should return correct summary") {
                val registry = PluginRegistry()

                repeat(3) { i ->
                    val plugin = MockPlugin("plugin-$i", "Plugin $i").apply {
                        enabled = i % 2 == 0
                    }
                    registry.register(plugin, PluginMetadata("plugin-$i", "Plugin $i", "", "1.0.0"))
                }

                val summary = registry.getSummary()
                summary.totalPlugins shouldBe 3
            }
        }
    }
})

// Test helper class
open class MockPlugin(
    override val id: String,
    override val name: String,
    override val description: String = "Test plugin"
) : Plugin {
    override val commands: List<PluginCommand> = emptyList()
    override var enabled: Boolean = false

    override suspend fun initialize(config: Map<String, String>) {
        enabled = true
    }

    override suspend fun shutdown() {
        enabled = false
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return PluginResult(true, data = "Executed: $command")
    }

    override fun shouldHandle(message: String): Boolean = false
}
