package ai.claudeflow.api.exception

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.HttpStatus

/**
 * API 에러 응답 모델
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, Any>? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun notFound(resource: String, id: String) = ApiError(
            code = "NOT_FOUND",
            message = "$resource not found: $id"
        )

        fun badRequest(message: String, details: Map<String, Any>? = null) = ApiError(
            code = "BAD_REQUEST",
            message = message,
            details = details
        )

        fun rateLimited(projectId: String) = ApiError(
            code = "RATE_LIMITED",
            message = "Rate limit exceeded for project: $projectId"
        )

        fun timeout(operation: String, timeoutSeconds: Long) = ApiError(
            code = "TIMEOUT",
            message = "$operation timed out after ${timeoutSeconds}s"
        )

        fun internal(message: String = "An internal error occurred") = ApiError(
            code = "INTERNAL_ERROR",
            message = message
        )

        fun executionFailed(error: String?) = ApiError(
            code = "EXECUTION_FAILED",
            message = error ?: "Claude execution failed"
        )
    }
}

/**
 * API 예외 클래스
 */
class ApiException(
    val error: ApiError,
    val status: HttpStatus
) : RuntimeException(error.message) {

    companion object {
        fun notFound(resource: String, id: String) = ApiException(
            ApiError.notFound(resource, id),
            HttpStatus.NOT_FOUND
        )

        fun badRequest(message: String, details: Map<String, Any>? = null) = ApiException(
            ApiError.badRequest(message, details),
            HttpStatus.BAD_REQUEST
        )

        fun rateLimited(projectId: String) = ApiException(
            ApiError.rateLimited(projectId),
            HttpStatus.TOO_MANY_REQUESTS
        )

        fun timeout(operation: String, timeoutSeconds: Long) = ApiException(
            ApiError.timeout(operation, timeoutSeconds),
            HttpStatus.GATEWAY_TIMEOUT
        )

        fun internal(message: String = "An internal error occurred") = ApiException(
            ApiError.internal(message),
            HttpStatus.INTERNAL_SERVER_ERROR
        )
    }
}
