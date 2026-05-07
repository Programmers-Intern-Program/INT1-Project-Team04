package com.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.in.RunDueSchedulesUseCase;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class BackApplicationTests extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Application: 헬스 체크가 정상 응답한다")
    void contextLoads() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Application: AiDataHub 기반 구 스케줄 실행 흐름은 빈으로 등록하지 않는다")
    void legacyAiDataHubScheduleExecutionFlowIsNotRegistered() {
        assertThat(applicationContext.getBeansOfType(RunDueSchedulesUseCase.class)).isEmpty();
    }

}
