package ai.claudeflow.core.rag

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 코드 청킹 서비스
 *
 * 코드를 의미 단위(클래스, 함수, 설정 블록 등)로 분할하여
 * 벡터 검색에 적합한 청크 생성
 */
class CodeChunker(
    private val maxChunkSize: Int = 1500,
    private val minChunkSize: Int = 100,
    private val overlapSize: Int = 100
) {
    /**
     * 파일 내용을 청크로 분할
     */
    fun chunkFile(
        content: String,
        filePath: String
    ): List<CodeChunk> {
        val language = detectLanguage(filePath)
        val extension = filePath.substringAfterLast('.', "")

        return when (language) {
            Language.KOTLIN, Language.JAVA -> chunkJvmCode(content, filePath, language)
            Language.TYPESCRIPT, Language.JAVASCRIPT -> chunkJsCode(content, filePath, language)
            Language.PYTHON -> chunkPythonCode(content, filePath)
            Language.GO -> chunkGoCode(content, filePath)
            Language.CONFIG -> chunkConfigFile(content, filePath, extension)
            else -> chunkGeneric(content, filePath)
        }
    }

    /**
     * JVM 언어 (Kotlin, Java) 청킹
     */
    private fun chunkJvmCode(
        content: String,
        filePath: String,
        language: Language
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        // 클래스/인터페이스/함수 단위로 분할
        val patterns = listOf(
            Regex("""^\s*(class|interface|object|enum)\s+\w+"""),
            Regex("""^\s*(fun|override fun|suspend fun)\s+\w+"""),
            Regex("""^\s*(val|var)\s+\w+\s*:"""),  // top-level properties
            Regex("""^\s*companion\s+object""")
        )

        var currentChunkLines = mutableListOf<String>()
        var currentStartLine = 1
        var braceCount = 0

        for ((index, line) in lines.withIndex()) {
            val lineNum = index + 1

            // 새로운 블록 시작 감지
            val isNewBlock = patterns.any { it.containsMatchIn(line) } && braceCount == 0

            if (isNewBlock && currentChunkLines.isNotEmpty()) {
                // 이전 청크 저장
                val chunkContent = currentChunkLines.joinToString("\n")
                if (chunkContent.length >= minChunkSize) {
                    chunks.add(createChunk(
                        content = chunkContent,
                        filePath = filePath,
                        startLine = currentStartLine,
                        endLine = lineNum - 1,
                        language = language.name.lowercase(),
                        chunkType = detectChunkType(currentChunkLines.first())
                    ))
                }
                currentChunkLines = mutableListOf()
                currentStartLine = lineNum
            }

            currentChunkLines.add(line)
            braceCount += line.count { it == '{' } - line.count { it == '}' }

            // 최대 크기 초과 시 강제 분할
            if (currentChunkLines.joinToString("\n").length > maxChunkSize && braceCount == 0) {
                val chunkContent = currentChunkLines.joinToString("\n")
                chunks.add(createChunk(
                    content = chunkContent,
                    filePath = filePath,
                    startLine = currentStartLine,
                    endLine = lineNum,
                    language = language.name.lowercase(),
                    chunkType = detectChunkType(currentChunkLines.first())
                ))
                currentChunkLines = mutableListOf()
                currentStartLine = lineNum + 1
            }
        }

        // 마지막 청크
        if (currentChunkLines.isNotEmpty()) {
            val chunkContent = currentChunkLines.joinToString("\n")
            if (chunkContent.length >= minChunkSize) {
                chunks.add(createChunk(
                    content = chunkContent,
                    filePath = filePath,
                    startLine = currentStartLine,
                    endLine = lines.size,
                    language = language.name.lowercase(),
                    chunkType = detectChunkType(currentChunkLines.first())
                ))
            }
        }

        return chunks
    }

    /**
     * JavaScript/TypeScript 청킹
     */
    private fun chunkJsCode(
        content: String,
        filePath: String,
        language: Language
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        val patterns = listOf(
            Regex("""^\s*(export\s+)?(async\s+)?function\s+\w+"""),
            Regex("""^\s*(export\s+)?(class|interface)\s+\w+"""),
            Regex("""^\s*(export\s+)?const\s+\w+\s*="""),
            Regex("""^\s*(export\s+)?type\s+\w+\s*=""")
        )

        var currentChunkLines = mutableListOf<String>()
        var currentStartLine = 1
        var braceCount = 0

        for ((index, line) in lines.withIndex()) {
            val lineNum = index + 1
            val isNewBlock = patterns.any { it.containsMatchIn(line) } && braceCount == 0

            if (isNewBlock && currentChunkLines.isNotEmpty()) {
                val chunkContent = currentChunkLines.joinToString("\n")
                if (chunkContent.length >= minChunkSize) {
                    chunks.add(createChunk(
                        content = chunkContent,
                        filePath = filePath,
                        startLine = currentStartLine,
                        endLine = lineNum - 1,
                        language = language.name.lowercase(),
                        chunkType = detectChunkType(currentChunkLines.first())
                    ))
                }
                currentChunkLines = mutableListOf()
                currentStartLine = lineNum
            }

            currentChunkLines.add(line)
            braceCount += line.count { it == '{' } - line.count { it == '}' }
        }

        // 마지막 청크
        if (currentChunkLines.isNotEmpty()) {
            val chunkContent = currentChunkLines.joinToString("\n")
            if (chunkContent.length >= minChunkSize) {
                chunks.add(createChunk(
                    content = chunkContent,
                    filePath = filePath,
                    startLine = currentStartLine,
                    endLine = lines.size,
                    language = language.name.lowercase(),
                    chunkType = detectChunkType(currentChunkLines.first())
                ))
            }
        }

        return chunks
    }

    /**
     * Python 코드 청킹
     */
    private fun chunkPythonCode(content: String, filePath: String): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        val patterns = listOf(
            Regex("""^class\s+\w+"""),
            Regex("""^def\s+\w+"""),
            Regex("""^async\s+def\s+\w+""")
        )

        var currentChunkLines = mutableListOf<String>()
        var currentStartLine = 1
        var currentIndent = 0

        for ((index, line) in lines.withIndex()) {
            val lineNum = index + 1
            val lineIndent = line.takeWhile { it == ' ' || it == '\t' }.length
            val isNewBlock = patterns.any { it.containsMatchIn(line) }

            if (isNewBlock && currentChunkLines.isNotEmpty()) {
                val chunkContent = currentChunkLines.joinToString("\n")
                if (chunkContent.length >= minChunkSize) {
                    chunks.add(createChunk(
                        content = chunkContent,
                        filePath = filePath,
                        startLine = currentStartLine,
                        endLine = lineNum - 1,
                        language = "python",
                        chunkType = detectChunkType(currentChunkLines.first())
                    ))
                }
                currentChunkLines = mutableListOf()
                currentStartLine = lineNum
                currentIndent = lineIndent
            }

            currentChunkLines.add(line)
        }

        // 마지막 청크
        if (currentChunkLines.isNotEmpty()) {
            val chunkContent = currentChunkLines.joinToString("\n")
            if (chunkContent.length >= minChunkSize) {
                chunks.add(createChunk(
                    content = chunkContent,
                    filePath = filePath,
                    startLine = currentStartLine,
                    endLine = lines.size,
                    language = "python",
                    chunkType = detectChunkType(currentChunkLines.first())
                ))
            }
        }

        return chunks
    }

    /**
     * Go 코드 청킹
     */
    private fun chunkGoCode(content: String, filePath: String): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        val patterns = listOf(
            Regex("""^func\s+"""),
            Regex("""^type\s+\w+\s+(struct|interface)""")
        )

        var currentChunkLines = mutableListOf<String>()
        var currentStartLine = 1
        var braceCount = 0

        for ((index, line) in lines.withIndex()) {
            val lineNum = index + 1
            val isNewBlock = patterns.any { it.containsMatchIn(line) } && braceCount == 0

            if (isNewBlock && currentChunkLines.isNotEmpty()) {
                val chunkContent = currentChunkLines.joinToString("\n")
                if (chunkContent.length >= minChunkSize) {
                    chunks.add(createChunk(
                        content = chunkContent,
                        filePath = filePath,
                        startLine = currentStartLine,
                        endLine = lineNum - 1,
                        language = "go",
                        chunkType = detectChunkType(currentChunkLines.first())
                    ))
                }
                currentChunkLines = mutableListOf()
                currentStartLine = lineNum
            }

            currentChunkLines.add(line)
            braceCount += line.count { it == '{' } - line.count { it == '}' }
        }

        // 마지막 청크
        if (currentChunkLines.isNotEmpty()) {
            val chunkContent = currentChunkLines.joinToString("\n")
            if (chunkContent.length >= minChunkSize) {
                chunks.add(createChunk(
                    content = chunkContent,
                    filePath = filePath,
                    startLine = currentStartLine,
                    endLine = lines.size,
                    language = "go",
                    chunkType = detectChunkType(currentChunkLines.first())
                ))
            }
        }

        return chunks
    }

    /**
     * 설정 파일 청킹 (YAML, JSON, TOML 등)
     */
    private fun chunkConfigFile(
        content: String,
        filePath: String,
        extension: String
    ): List<CodeChunk> {
        // 설정 파일은 전체를 하나의 청크로 (크기 제한 내에서)
        return if (content.length <= maxChunkSize) {
            listOf(createChunk(
                content = content,
                filePath = filePath,
                startLine = 1,
                endLine = content.lines().size,
                language = extension,
                chunkType = "config"
            ))
        } else {
            // 크기 초과 시 일반 청킹
            chunkGeneric(content, filePath)
        }
    }

    /**
     * 일반 텍스트 청킹 (고정 크기)
     */
    private fun chunkGeneric(content: String, filePath: String): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()
        var startLine = 1
        var currentContent = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            val lineNum = index + 1

            if (currentContent.length + line.length > maxChunkSize) {
                if (currentContent.isNotEmpty()) {
                    chunks.add(createChunk(
                        content = currentContent.toString(),
                        filePath = filePath,
                        startLine = startLine,
                        endLine = lineNum - 1,
                        language = filePath.substringAfterLast('.'),
                        chunkType = "generic"
                    ))
                }
                currentContent = StringBuilder()
                startLine = lineNum
            }

            currentContent.appendLine(line)
        }

        // 마지막 청크
        if (currentContent.isNotEmpty()) {
            chunks.add(createChunk(
                content = currentContent.toString().trimEnd(),
                filePath = filePath,
                startLine = startLine,
                endLine = lines.size,
                language = filePath.substringAfterLast('.'),
                chunkType = "generic"
            ))
        }

        return chunks
    }

    private fun createChunk(
        content: String,
        filePath: String,
        startLine: Int,
        endLine: Int,
        language: String,
        chunkType: String
    ): CodeChunk {
        return CodeChunk(
            filePath = filePath,
            content = content,
            startLine = startLine,
            endLine = endLine,
            language = language,
            chunkType = chunkType,
            contentPreview = content.take(200).replace("\n", " ")
        )
    }

    private fun detectLanguage(filePath: String): Language {
        val extension = filePath.substringAfterLast('.').lowercase()
        return when (extension) {
            "kt", "kts" -> Language.KOTLIN
            "java" -> Language.JAVA
            "ts", "tsx" -> Language.TYPESCRIPT
            "js", "jsx", "mjs" -> Language.JAVASCRIPT
            "py" -> Language.PYTHON
            "go" -> Language.GO
            "yaml", "yml", "json", "toml", "xml", "properties" -> Language.CONFIG
            else -> Language.UNKNOWN
        }
    }

    private fun detectChunkType(firstLine: String): String {
        val line = firstLine.trim().lowercase()
        return when {
            line.contains("class ") -> "class"
            line.contains("interface ") -> "interface"
            line.contains("object ") -> "object"
            line.contains("enum ") -> "enum"
            line.contains("fun ") || line.contains("function ") || line.contains("def ") -> "function"
            line.contains("const ") || line.contains("val ") || line.contains("var ") -> "property"
            line.contains("type ") -> "type"
            else -> "block"
        }
    }

    enum class Language {
        KOTLIN, JAVA, TYPESCRIPT, JAVASCRIPT, PYTHON, GO, CONFIG, UNKNOWN
    }
}

/**
 * 코드 청크
 */
data class CodeChunk(
    val filePath: String,
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val language: String,
    val chunkType: String,
    val contentPreview: String,
    val score: Float = 0f  // 검색 시 유사도 점수
)
