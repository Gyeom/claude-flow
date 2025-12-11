package ai.claudeflow.api.slack

import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.reactions.ReactionsAddRequest
import com.slack.api.methods.request.reactions.ReactionsRemoveRequest
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Slack 메시지 발송기
 */
class SlackMessageSender(
    botToken: String
) {
    private val client: MethodsClient = Slack.getInstance().methods(botToken)

    /**
     * 메시지 전송
     */
    fun sendMessage(
        channel: String,
        text: String,
        threadTs: String? = null,
        blocks: String? = null
    ): Result<String> {
        return try {
            val request = ChatPostMessageRequest.builder()
                .channel(channel)
                .text(text)
                .apply {
                    threadTs?.let { threadTs(it) }
                    blocks?.let { blocksAsString(it) }
                }
                .build()

            val response = client.chatPostMessage(request)

            if (response.isOk) {
                logger.info { "Message sent to $channel: ts=${response.ts}" }
                Result.success(response.ts)
            } else {
                logger.error { "Failed to send message: ${response.error}" }
                Result.failure(RuntimeException(response.error))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send message to $channel" }
            Result.failure(e)
        }
    }

    /**
     * 리액션 추가
     */
    fun addReaction(
        channel: String,
        timestamp: String,
        emoji: String
    ): Boolean {
        return try {
            val response = client.reactionsAdd(
                ReactionsAddRequest.builder()
                    .channel(channel)
                    .timestamp(timestamp)
                    .name(emoji)
                    .build()
            )

            if (response.isOk) {
                logger.debug { "Reaction '$emoji' added to $channel:$timestamp" }
                true
            } else {
                // already_reacted는 정상으로 처리
                if (response.error == "already_reacted") {
                    true
                } else {
                    logger.warn { "Failed to add reaction: ${response.error}" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to add reaction '$emoji'" }
            false
        }
    }

    /**
     * 리액션 제거
     */
    fun removeReaction(
        channel: String,
        timestamp: String,
        emoji: String
    ): Boolean {
        return try {
            val response = client.reactionsRemove(
                ReactionsRemoveRequest.builder()
                    .channel(channel)
                    .timestamp(timestamp)
                    .name(emoji)
                    .build()
            )

            if (response.isOk || response.error == "no_reaction") {
                logger.debug { "Reaction '$emoji' removed from $channel:$timestamp" }
                true
            } else {
                logger.warn { "Failed to remove reaction: ${response.error}" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to remove reaction '$emoji'" }
            false
        }
    }
}
