plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.2.3.7755"
    jacoco
}

group = "org.machikoro"
version = "0.0.1-SNAPSHOT"
description = "server"

val springdocVersion = "3.0.2"
val dotenvVersion = "5.2.2"
val mockitoKotlinVersion = "5.2.1"
val exposedVersion = "1.0.0"
val testcontainersVersion = "1.20.4"
val springwolfVersion = "2.3.0"


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

sonar {
    properties {
        property("sonar.projectKey", "SE2-Machi-Koro_Server")
        property("sonar.organization", "se2-machi-koro")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.sources", "src/main/kotlin,src/main/resources")
        property("sonar.tests", "src/test/kotlin")
        property("sonar.test.inclusions", "src/test/kotlin/**/*.kt")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.coverage.exclusions", "src/main/kotlin/**/dto/**,src/main/kotlin/**/config/**,src/main/kotlin/**/ServerApplication.kt")
        property("sonar.newCode.referenceBranch", "main")
        property("sonar.qualitygate.wait", "true")
        property("sonar.issue.ignore.multicriteria", "e1")
        property("sonar.issue.ignore.multicriteria.e1.ruleKey", "sql:S1192")
        property(
            "sonar.issue.ignore.multicriteria.e1.resourceKey",
            "src/main/resources/db/migration/V1__init_schema.sql",
        )
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.cdimascio:java-dotenv:$dotenvVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    implementation("io.github.springwolf:springwolf-stomp:${springwolfVersion}")
    implementation("io.github.springwolf:springwolf-stomp-binding:${springwolfVersion}")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("io.github.springwolf:springwolf-ui:${springwolfVersion}")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.bootJar {
    layered {
        enabled.set(true)
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.sonar {
    dependsOn(tasks.jacocoTestReport)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "CLASS"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}