package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DisplayName("Bootstrap: Flyway MCP V2 마이그레이션 테스트")
@Testcontainers
class FlywayMcpMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("int1_flyway_mcp_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @DisplayName("V2 가 mcp_server / mcp_tool 테이블을 만들고 search_house_price 시드를 적재한다")
    void migratesMcpTablesAndSeedsSearchHousePrice() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        JdbcTemplate jdbcTemplate = jdbcTemplate();

        Integer v2Applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = true",
                Integer.class
        );
        assertThat(v2Applied).isEqualTo(1);

        Integer serverCount = jdbcTemplate.queryForObject(
                "select count(*) from mcp_server where name = 'monitoring-mcp'",
                Integer.class
        );
        assertThat(serverCount).isEqualTo(1);

        String toolName = jdbcTemplate.queryForObject(
                "select name from mcp_tool where id = 1",
                String.class
        );
        assertThat(toolName).isEqualTo("search_house_price");

        String domainName = jdbcTemplate.queryForObject(
                """
                        select d.name from mcp_tool t
                        join domain d on d.id = t.domain_id
                        where t.name = 'search_house_price'
                        """,
                String.class
        );
        assertThat(domainName).isEqualTo("real-estate");

        Boolean hasRegion = jdbcTemplate.queryForObject(
                "select (input_schema -> 'properties' -> 'region') is not null from mcp_tool where name = 'search_house_price'",
                Boolean.class
        );
        Boolean hasDealYmd = jdbcTemplate.queryForObject(
                "select (input_schema -> 'properties' -> 'deal_ymd') is not null from mcp_tool where name = 'search_house_price'",
                Boolean.class
        );
        assertThat(hasRegion).isTrue();
        assertThat(hasDealYmd).isTrue();
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
