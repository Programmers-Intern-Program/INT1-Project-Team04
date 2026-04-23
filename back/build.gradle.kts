import org.gradle.kotlin.dsl.testImplementation

plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

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
    }
}

dependencies {
    dependencies {
        // Web & Core
        implementation("org.springframework.boot:spring-boot-starter-webmvc")
        implementation("com.fasterxml.jackson.core:jackson-databind")
        implementation("org.springframework.boot:spring-boot-starter-validation")
        implementation("org.springframework.boot:spring-boot-starter-actuator")
        compileOnly("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")


        // DB
        implementation("org.springframework.boot:spring-boot-starter-data-jpa")
        runtimeOnly("org.postgresql:postgresql")
        developmentOnly("org.testcontainers:postgresql")
        developmentOnly("org.testcontainers:jdbc")

        // 테스트 환경
        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
        testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
        testImplementation("org.testcontainers:junit-jupiter")
        testImplementation("org.testcontainers:postgresql")
        testImplementation("org.testcontainers:jdbc")
        testCompileOnly("org.projectlombok:lombok")
        testAnnotationProcessor("org.projectlombok:lombok")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

}

tasks.withType<Test> {
    useJUnitPlatform {
        excludeTags("ai-manual")
    }
}

tasks.register<Test>("aiTest") {
    useJUnitPlatform {
        includeTags("ai-manual")
    }
}
