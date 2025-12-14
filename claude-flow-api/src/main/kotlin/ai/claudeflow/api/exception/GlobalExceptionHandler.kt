package ai.claudeflow.api.exception

import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * 전역 예외 처리기
 *
 * 모든 컨트롤러에서 발생하는 예외를 일관된 형식으로 처리
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    /**
     * ApiException 처리
     */
    @ExceptionHandler(ApiException::class)
    fun handleApiException(e: ApiException): ResponseEntity<ApiError> {
        logger.warn { "API Exception: ${e.error.code} - ${e.error.message}" }
        return ResponseEntity.status(e.status).body(e.error)
    }

    /**
     * 유효성 검증 실패 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val errors = e.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "Invalid value")
        }
        logger.warn { "Validation failed: $errors" }

        return ResponseEntity.badRequest().body(
            ApiError.badRequest("Validation failed", errors)
        )
    }

    /**
     * 입력 파싱 오류 처리
     */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleInputException(e: ServerWebInputException): ResponseEntity<ApiError> {
        logger.warn { "Input error: ${e.message}" }
        return ResponseEntity.badRequest().body(
            ApiError.badRequest("Invalid request body: ${e.reason}")
        )
    }

    /**
     * 타임아웃 처리
     */
    @ExceptionHandler(TimeoutException::class)
    fun handleTimeoutException(e: TimeoutException): ResponseEntity<ApiError> {
        logger.error { "Timeout: ${e.message}" }
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(
            ApiError.timeout("Operation", 300)
        )
    }

    /**
     * IllegalArgumentException 처리
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<ApiError> {
        logger.warn { "Illegal argument: ${e.message}" }
        return ResponseEntity.badRequest().body(
            ApiError.badRequest(e.message ?: "Invalid argument")
        )
    }

    /**
     * 기타 모든 예외 처리
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(e: Exception): ResponseEntity<ApiError> {
        logger.error(e) { "Unhandled exception: ${e.javaClass.simpleName}" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError.internal("An unexpected error occurred. Please try again later.")
        )
    }
}
