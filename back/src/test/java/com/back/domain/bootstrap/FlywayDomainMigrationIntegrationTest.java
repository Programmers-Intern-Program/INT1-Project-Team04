package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@DisplayName("Bootstrap: Flyway 도메인 마이그레이션 테스트")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:18:///int1_flyway_seed_test",
        "spring.jpa.hibernate.ddl-auto=update"
})
class FlywayDomainMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    @Test
    @DisplayName("앱 시작 시 Flyway 마이그레이션 이력이 기록되고 기본 도메인 4개가 준비된다")
    void migratesDefaultDomainsWithFlyway() {
        Integer appliedCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1'",
                Integer.class
        );

        assertThat(appliedCount).isEqualTo(1);
        assertThat(domainJpaRepository.findAll(Sort.by(Sort.Direction.ASC, "id")))
                .extracting("id", "name")
                .containsExactly(
                        tuple(1L, "real-estate"),
                        tuple(2L, "law-regulation"),
                        tuple(3L, "recruitment"),
                        tuple(4L, "auction")
                );
    }
}
