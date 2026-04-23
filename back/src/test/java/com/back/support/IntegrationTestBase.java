package com.back.support;

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
