package com.back.support;

import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
public abstract class IntegrationTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    private DatabaseCleanup databaseCleanup;

    /*
     * 중복되는 Mockito Bean 선언 위치
     */
    @MockitoBean
    protected Clock clock;

    // VertexAiTaskParserAdapter가 @Profile("!test")로 비활성화되어 있으므로
    // 전체 컨텍스트를 로드하는 통합 테스트에서 ParseNaturalLanguagePort 빈이 없어 실패하는 것을 방지.
    // ParseTaskControllerTest처럼 자체 mock이 있는 테스트는 @MockitoBean이 그 빈을 대체하므로 영향 없음.
    @MockitoBean
    protected ParseNaturalLanguagePort parseNaturalLanguagePort;

    @AfterEach
    void cleanupDatabase() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            databaseCleanup.execute();
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
