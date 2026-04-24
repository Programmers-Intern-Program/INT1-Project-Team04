package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DisplayName("Bootstrap: Flyway 도메인 마이그레이션 테스트")
@Testcontainers
class FlywayDomainMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("int1_flyway_seed_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @DisplayName("Flyway 마이그레이션 이력이 기록되고 기본 도메인 4개가 준비된다")
    void migratesDefaultDomainsWithFlyway() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        Integer appliedCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class
        );

        assertThat(appliedCount).isEqualTo(1);
        assertThat(jdbcTemplate.query(
                "select id, name from domain order by id",
                (rs, rowNum) -> tuple(rs.getLong("id"), rs.getString("name"))
        ))
                .containsExactly(
                        tuple(1L, "real-estate"),
                        tuple(2L, "law-regulation"),
                        tuple(3L, "recruitment"),
                        tuple(4L, "auction")
                );
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
