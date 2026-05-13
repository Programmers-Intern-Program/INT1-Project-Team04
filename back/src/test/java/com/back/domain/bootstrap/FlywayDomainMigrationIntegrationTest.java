package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.support.TestOAuthProviderConfiguration;
import com.back.support.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Bootstrap: Flyway 도메인 마이그레이션 테스트")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestOAuthProviderConfiguration.class})
class FlywayDomainMigrationIntegrationTest {

    @MockitoBean
    private ParseNaturalLanguagePort parseNaturalLanguagePort;

    @MockitoBean
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Flyway 마이그레이션 이력이 기록되고 기본 도메인 4개가 준비된다")
    void migratesDefaultDomainsWithFlyway() throws Exception {
        String testDbName = "flyway_domain_test";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + testDbName);
            stmt.execute("CREATE DATABASE " + testDbName);
        }

        HikariDataSource original = (HikariDataSource) dataSource;
        String originalUrl = original.getJdbcUrl();
        int dbStart = originalUrl.lastIndexOf('/');
        int queryStart = originalUrl.indexOf('?', dbStart);
        String testUrl = originalUrl.substring(0, dbStart + 1) + testDbName
                + (queryStart >= 0 ? originalUrl.substring(queryStart) : "");

        HikariDataSource testDs = new HikariDataSource();
        testDs.setJdbcUrl(testUrl);
        testDs.setUsername(original.getUsername());
        testDs.setPassword(original.getPassword());

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(testDs)
                    .locations("classpath:db/migration")
                    .placeholderReplacement(false)
                    .cleanDisabled(false)
                    .load();
            flyway.clean();
            flyway.migrate();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(testDs);
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
            testDs.close();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS " + testDbName);
            }
        }
    }
}
