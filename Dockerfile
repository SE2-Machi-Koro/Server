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
RUN --mount=type=cache,target=/root/.gradle \
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

FROM runtime-base AS runtime-from-workspace
COPY --chown=app:app --from=packaged-jar /app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM runtime-base AS runtime-from-builder
COPY --chown=app:app --from=builder /app/build/libs/*.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM runtime-from-builder AS final