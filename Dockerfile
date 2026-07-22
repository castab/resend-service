# syntax=docker/dockerfile:1.7
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src src
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon nativeCompile --no-configuration-cache

FROM oraclelinux:9-slim AS runner
RUN microdnf install -y shadow-utils && microdnf clean all && useradd --system --uid 1001 app
WORKDIR /app
COPY --from=builder --chown=app:app /workspace/build/native/nativeCompile/resend-service /app/resend-service
USER app
ENV PORT=3000 HOST=0.0.0.0
EXPOSE 3000
ENTRYPOINT ["/app/resend-service", "-Xms16m", "-Xmx128m"]
