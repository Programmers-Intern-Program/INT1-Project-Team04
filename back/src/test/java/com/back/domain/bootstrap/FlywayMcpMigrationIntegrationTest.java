package com.back.domain.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.support.TestOAuthProviderConfiguration;
import com.back.support.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
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

@DisplayName("Bootstrap: Flyway MCP 마이그레이션 테스트")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestOAuthProviderConfiguration.class})
class FlywayMcpMigrationIntegrationTest {

    @MockitoBean
    private ParseNaturalLanguagePort parseNaturalLanguagePort;

    @MockitoBean
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("MCP 마이그레이션은 mcp_server / mcp_tool 테이블과 부동산 도구 시드를 적재한다")
    void migratesMcpTablesAndSeedsSearchHousePrice() throws Exception {
        String testDbName = "flyway_mcp_test";

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
                    .load();
            flyway.migrate();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(testDs);

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
        } finally {
            testDs.close();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS " + testDbName);
            }
        }
    }
}
