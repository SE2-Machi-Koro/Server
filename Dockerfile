FROM eclipse-temurin:21.0.6_7-jdk-jammy AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src
RUN chmod +x ./gradlew
RUN ./gradlew bootJar -x test

FROM eclipse-temurin:21.0.6_7-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --no-log-init --system --create-home --home-dir /app --gid app app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown -R app:app /app
EXPOSE 8080
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]