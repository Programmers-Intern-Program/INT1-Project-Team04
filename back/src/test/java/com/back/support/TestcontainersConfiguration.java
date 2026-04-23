package com.back.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

@TestConfiguration(proxyBeanMethods = false) // Spring이 CGLIB 프록시를 생성하지 않아 시작 속도가 빨라집니다.
public class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw")) // 파일이 저장되는 디렉토리를 메모리(RAM)에 할당
                .withCommand("postgres -c max_connections=300") //테스트가 병렬로 돌아갈 때 발생할 수 있는 Too many connections 에러를 미리 방지
                .withReuse(true); //실제 작동하게 하려면 홈 디렉토리에 .testcontainers.properties에 testcontainers.reuse.enable=true
        // 추가 필요.
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withTmpFs(Map.of("/data", "rw"))
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
                .withReuse(true);
    }
}
