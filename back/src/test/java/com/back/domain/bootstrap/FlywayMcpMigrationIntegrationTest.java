package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DisplayName("Bootstrap: Flyway MCP 마이그레이션 테스트")
@Testcontainers
class FlywayMcpMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("int1_flyway_mcp_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @DisplayName("MCP 마이그레이션은 mcp_server / mcp_tool 테이블과 부동산 도구 시드를 적재한다")
    void migratesMcpTablesAndSeedsSearchHousePrice() throws Exception {
        System.err.println("[DEBUG] JDBC URL: " + postgres.getJdbcUrl());
        System.err.println("[DEBUG] Attempting raw JDBC connection...");
        try {
            var conn = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            System.err.println("[DEBUG] Raw JDBC connected OK: " + conn.getMetaData().getDatabaseProductName());
            conn.close();
        } catch (Exception e) {
            System.err.println("[DEBUG] Raw JDBC FAILED: " + e.getClass().getName() + ": " + e.getMessage());
        }

        System.err.println("[DEBUG] Starting Flyway migration...");
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .placeholderReplacement(false)
                    .load();
            var result = flyway.migrate();
            System.err.println("[DEBUG] Flyway migrate success. Applied: " + result.migrationsExecuted);
        } catch (Exception e) {
            System.err.println("[DEBUG] ===== FLYWAY FAILURE =====");
            System.err.println("[DEBUG] Exception: " + e.getClass().getName());
            System.err.println("[DEBUG] Message: " + e.getMessage());
            Throwable current = e.getCause();
            while (current != null) {
                System.err.println("[DEBUG] Caused by: " + current.getClass().getName() + ": " + current.getMessage());
                current = current.getCause();
            }
            e.printStackTrace(System.err);
            throw e;
        }

        JdbcTemplate jdbcTemplate = jdbcTemplate();

        Integer v2Applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = true",
                Integer.class
        );
        assertThat(v2Applied).isEqualTo(1);
        Integer v4Applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '4' and success = true",
                Integer.class
        );
        assertThat(v4Applied).isEqualTo(1);

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
        List<String> realEstateToolNames = jdbcTemplate.queryForList(
                """
                        select t.name from mcp_tool t
                        join domain d on d.id = t.domain_id
                        where d.name = 'real-estate'
                        order by t.name
                        """,
                String.class
        );
        assertThat(realEstateToolNames)
                .contains(
                        "search_house_price",
                        "search_apt_rent",
                        "search_offi_trade",
                        "search_offi_rent",
                        "search_rh_trade",
                        "search_rh_rent"
                );

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
