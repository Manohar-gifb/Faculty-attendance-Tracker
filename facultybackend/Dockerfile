# ============================================================
# Backend Dockerfile (Multi-stage build)
# Stage 1: Build fat jar using Temurin JDK 17
# Stage 2: Minimal hardened Temurin JRE 17 runtime
# ============================================================

# Stage 1: Build
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper and POM first for layer caching
COPY pom.xml mvnw ./
COPY .mvn .mvn

RUN chmod +x ./mvnw
RUN ./mvnw dependency:go-offline -B || true

# Copy backend source code
COPY src src

# Package production fat jar without running tests (tests run in CI/GSD pipeline)
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Production Runtime
FROM eclipse-temurin:17-jre-jammy

# Install curl for healthcheck probe
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root system user for least privilege security
RUN useradd -r -u 1001 -d /app -s /sbin/nologin spring

WORKDIR /app

# Copy compiled jar from builder stage
COPY --from=builder --chown=spring:root /build/target/facultybackend-*.jar app.jar

# Create default upload directory and ensure correct ownership
RUN mkdir -p /var/faculty/uploads/profile-pictures \
    && chown -R spring:root /var/faculty \
    && chmod -R 775 /var/faculty

# Run as non-root user
USER spring:root

EXPOSE 8081

# Default runtime configuration
ENV SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/var/faculty/uploads/profile-pictures

# Health check probe hitting unauthenticated /api/health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8081/api/health || exit 1

# Container JVM memory tuning: automatically limits heap to 75% of container RAM
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
