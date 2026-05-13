package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("Bootstrap: Flyway 도메인 마이그레이션 테스트")
class FlywayDomainMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @DisplayName("Flyway 마이그레이션 이력이 기록되고 기본 도메인 4개가 준비된다")
    void migratesDefaultDomainsWithFlyway() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl());
        ds.setUsername(pg.getUsername());
        ds.setPassword(pg.getPassword());

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .placeholderReplacement(false)
                    .load();
            flyway.migrate();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);

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
        } finally {
            ds.close();
        }
    }
}
