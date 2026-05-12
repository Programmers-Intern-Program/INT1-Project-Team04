package com.back.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ApplicationMcpClientPropertiesTest {

    @Test
    @DisplayName("MCP client request timeout은 실제 외부 API/알림 발송 지연을 감당하도록 60초 기본값을 둔다")
    void mcpClientRequestTimeoutDefaultsToSixtySeconds() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));

        Properties properties = factory.getObject();

        assertThat(properties)
                .isNotNull()
                .containsEntry("spring.ai.mcp.client.request-timeout", "${MCP_CLIENT_REQUEST_TIMEOUT:60s}");
    }
}
