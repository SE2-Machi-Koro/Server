# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.6_7-jdk-jammy AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY gradle.lockfile .
COPY src src
RUN chmod +x ./gradlew
RUN --mount=type=cache,id=s/dfcbae23-f955-46ae-97ba-22310eb59b60-/root/.gradle,target=/root/.gradle \
    ./gradlew bootJar -x test

FROM scratch AS packaged-jar
COPY build/docker/app.jar /app.jar

FROM eclipse-temurin:21.0.6_7-jre-jammy AS runtime-base
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --no-log-init --system --create-home --home-dir /app --gid app app
EXPOSE 8080

# Explode the layered bootJar so Docker can cache slow-changing layers
# (dependencies) separately from fast-changing ones (application code).
FROM runtime-base AS extract-from-workspace
COPY --from=packaged-jar /app.jar /app/app.jar
RUN java -Djarmode=tools -jar /app/app.jar extract --layers --launcher --destination /app/extracted

FROM runtime-base AS extract-from-builder
COPY --from=builder /app/build/libs/*.jar /app/app.jar
RUN java -Djarmode=tools -jar /app/app.jar extract --layers --launcher --destination /app/extracted

FROM runtime-base AS runtime-from-workspace
# Make the JVM container-memory-aware: default heap is only ~25% of the
# container limit, which wastes most of the platform's RAM (e.g. on Railway).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
# Copy layers least-to-most likely to change so the cache survives code-only rebuilds.
COPY --chown=app:app --from=extract-from-workspace /app/extracted/dependencies/ ./
COPY --chown=app:app --from=extract-from-workspace /app/extracted/spring-boot-loader/ ./
COPY --chown=app:app --from=extract-from-workspace /app/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=extract-from-workspace /app/extracted/application/ ./
USER app
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

FROM runtime-base AS runtime-from-builder
# Make the JVM container-memory-aware: default heap is only ~25% of the
# container limit, which wastes most of the platform's RAM (e.g. on Railway).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
# Copy layers least-to-most likely to change so the cache survives code-only rebuilds.
COPY --chown=app:app --from=extract-from-builder /app/extracted/dependencies/ ./
COPY --chown=app:app --from=extract-from-builder /app/extracted/spring-boot-loader/ ./
COPY --chown=app:app --from=extract-from-builder /app/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=extract-from-builder /app/extracted/application/ ./
USER app
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

FROM runtime-from-builder AS final