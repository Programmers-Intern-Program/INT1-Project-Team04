package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.support.TestOAuthProviderConfiguration;
import com.back.support.TestcontainersConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Bootstrap: Flyway 도메인 마이그레이션 테스트")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Import({TestcontainersConfiguration.class, TestOAuthProviderConfiguration.class})
class FlywayDomainMigrationIntegrationTest {

    @MockitoBean
    private ParseNaturalLanguagePort parseNaturalLanguagePort;

    @MockitoBean
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 마이그레이션 이력이 기록되고 기본 도메인 4개가 준비된다")
    void migratesDefaultDomainsWithFlyway() {
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
}
