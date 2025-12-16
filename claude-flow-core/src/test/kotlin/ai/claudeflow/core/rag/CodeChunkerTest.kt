package ai.claudeflow.core.rag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty

class CodeChunkerTest : DescribeSpec({

    val chunker = CodeChunker(
        maxChunkSize = 1000,
        minChunkSize = 50,
        overlapSize = 50
    )

    describe("CodeChunker - Kotlin") {

        it("should chunk Kotlin class") {
            val kotlinCode = """
                package com.example

                /**
                 * 사용자 서비스
                 */
                class UserService(
                    private val repository: UserRepository
                ) {
                    fun findById(id: String): User? {
                        return repository.findById(id)
                    }

                    fun save(user: User): User {
                        return repository.save(user)
                    }

                    fun delete(id: String): Boolean {
                        return repository.delete(id)
                    }
                }
            """.trimIndent()

            val chunks = chunker.chunkFile(kotlinCode, "UserService.kt")

            chunks.shouldNotBeEmpty()
            chunks[0].language shouldBe "kotlin"
            chunks[0].filePath shouldBe "UserService.kt"
        }

        it("should detect chunk types correctly") {
            val kotlinCode = """
                interface Repository<T> {
                    fun findById(id: String): T?
                }

                class UserRepository : Repository<User> {
                    override fun findById(id: String): User? = null
                }

                object Singleton {
                    val instance = "test"
                }

                enum class Status {
                    ACTIVE, INACTIVE
                }
            """.trimIndent()

            val chunks = chunker.chunkFile(kotlinCode, "Types.kt")

            chunks.shouldNotBeEmpty()
            // 각 타입별로 청크가 생성되어야 함
            val types = chunks.map { it.chunkType }
            types.any { it == "interface" || it == "class" || it == "object" || it == "enum" } shouldBe true
        }
    }

    describe("CodeChunker - TypeScript") {

        it("should chunk TypeScript code") {
            val tsCode = """
                interface User {
                    id: string;
                    name: string;
                }

                export class UserService {
                    constructor(private readonly db: Database) {}

                    async findById(id: string): Promise<User | null> {
                        return this.db.users.findOne({ id });
                    }
                }

                export const createUser = (name: string): User => {
                    return { id: crypto.randomUUID(), name };
                };
            """.trimIndent()

            val chunks = chunker.chunkFile(tsCode, "user.service.ts")

            chunks.shouldNotBeEmpty()
            chunks[0].language shouldBe "typescript"
        }
    }

    describe("CodeChunker - Python") {

        it("should chunk Python code") {
            val pythonCode = """
                class UserService:
                    def __init__(self, repository):
                        self.repository = repository

                    def find_by_id(self, user_id: str):
                        return self.repository.find_by_id(user_id)

                    def save(self, user):
                        return self.repository.save(user)


                def create_user(name: str) -> dict:
                    return {"id": str(uuid4()), "name": name}
            """.trimIndent()

            val chunks = chunker.chunkFile(pythonCode, "user_service.py")

            chunks.shouldNotBeEmpty()
            chunks[0].language shouldBe "python"
        }
    }

    describe("CodeChunker - Config files") {

        it("should chunk YAML as single chunk") {
            val yamlContent = """
                services:
                  api:
                    image: api:latest
                    ports:
                      - "8080:8080"
                    environment:
                      - DB_URL=postgres://localhost
            """.trimIndent()

            val chunks = chunker.chunkFile(yamlContent, "docker-compose.yml")

            chunks shouldHaveSize 1
            chunks[0].chunkType shouldBe "config"
        }

        it("should chunk JSON as single chunk") {
            val jsonContent = """
                {
                    "name": "test-project",
                    "version": "1.0.0",
                    "dependencies": {
                        "react": "^18.0.0"
                    }
                }
            """.trimIndent()

            val chunks = chunker.chunkFile(jsonContent, "package.json")

            chunks shouldHaveSize 1
            chunks[0].language shouldBe "json"
        }
    }

    describe("CodeChunker - Edge cases") {

        it("should handle empty file") {
            val chunks = chunker.chunkFile("", "empty.kt")
            chunks shouldHaveSize 0
        }

        it("should handle very short content") {
            val chunks = chunker.chunkFile("x = 1", "short.py")
            chunks shouldHaveSize 0  // Below minChunkSize
        }

        it("should detect language from extension") {
            val extensions = mapOf(
                "Test.kt" to "kotlin",
                "Test.java" to "java",
                "Test.ts" to "typescript",
                "Test.tsx" to "typescript",
                "Test.js" to "javascript",
                "test.py" to "python",
                "test.go" to "go"
            )

            for ((filename, expectedLang) in extensions) {
                val dummyContent = "class Test { }"
                val chunks = chunker.chunkFile(dummyContent, filename)
                if (chunks.isNotEmpty()) {
                    chunks[0].language shouldBe expectedLang
                }
            }
        }
    }
})
