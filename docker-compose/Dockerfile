# Build stage
FROM gradle:8.13-jdk21 AS build

WORKDIR /app
COPY --chown=gradle:gradle . .

RUN gradle :claude-flow-app:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install Claude Code CLI
RUN apk add --no-cache nodejs npm bash git curl && \
    npm install -g @anthropic-ai/claude-code

# Copy application
COPY --from=build /app/claude-flow-app/build/libs/claude-flow.jar app.jar

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
