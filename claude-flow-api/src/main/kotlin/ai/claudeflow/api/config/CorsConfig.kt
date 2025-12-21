package ai.claudeflow.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

/**
 * CORS Configuration
 *
 * 기본값은 개발 환경용 localhost 포트들입니다.
 * 프로덕션에서는 환경변수로 설정하세요:
 *
 * CORS_ALLOWED_ORIGINS=https://dashboard.example.com,https://admin.example.com
 *
 * 또는 application.yml에서:
 * cors:
 *   allowed-origins: https://dashboard.example.com,https://admin.example.com
 */
@Configuration
class CorsConfig {

    companion object {
        // 개발 환경용 기본값 - 프로덕션에서는 반드시 환경변수로 설정 필요
        private const val DEFAULT_DEV_ORIGINS = "http://localhost:3000,http://localhost:3001,http://localhost:3002"
    }

    @Value("\${cors.allowed-origins:$DEFAULT_DEV_ORIGINS}")
    private lateinit var allowedOrigins: String

    @Bean
    fun corsWebFilter(): CorsWebFilter {
        val config = CorsConfiguration()

        // Parse comma-separated origins from environment variable
        val origins = allowedOrigins.split(",")
        for (origin in origins) {
            val trimmed = origin.trim()
            if (trimmed.isNotEmpty()) {
                config.addAllowedOrigin(trimmed)
            }
        }

        config.addAllowedMethod("*")
        config.addAllowedHeader("*")
        config.allowCredentials = true
        config.maxAge = 3600L

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)

        return CorsWebFilter(source)
    }
}
