import org.gradle.kotlin.dsl.testImplementation

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

val springAiVersion = "2.0.0-M4"

group = "com"
version = "0.0.1-SNAPSHOT"
description = "back"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
        mavenBom("org.springframework.ai:spring-ai-bom:${springAiVersion}")
    }
}

dependencies {
    // Web & Core
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // DB
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Spring AI (2.0.0-M4, Spring Boot 4.x 대응 아티팩트명)
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")

    // 테스트 환경
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:jdbc")
    testImplementation("com.icegreen:greenmail-junit5:2.1.3")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val liveTest by sourceSets.creating {
    java.srcDir("src/liveTest/java")
    resources.srcDir("src/liveTest/resources")
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[liveTest.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[liveTest.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("ai-manual")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("aiTest") {
    description = "Runs AI integration tests that require manual API keys"
    group = "verification"
    useJUnitPlatform {
        includeTags("ai-manual")
    }
}

tasks.register<Test>("liveNotificationTest") {
    description = "Runs opt-in smoke tests that send real notification messages to external providers."
    group = "verification"
    testClassesDirs = liveTest.output.classesDirs
    classpath = liveTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform {
        includeTags("live-notification")
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}