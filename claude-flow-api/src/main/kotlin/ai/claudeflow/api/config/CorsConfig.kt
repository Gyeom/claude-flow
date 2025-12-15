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
 * Configure allowed origins via environment variable:
 * CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:3001,https://dashboard.example.com
 */
@Configuration
class CorsConfig {

    @Value("\${cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:3002}")
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
