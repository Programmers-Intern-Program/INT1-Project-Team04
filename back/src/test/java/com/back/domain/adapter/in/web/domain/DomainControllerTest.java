package com.back.domain.adapter.in.web.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.domain.DomainJpaEntity;
import com.back.domain.adapter.out.persistence.domain.DomainJpaRepository;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Web: 도메인 조회 API 테스트")
class DomainControllerTest extends IntegrationTestBase {

    @Autowired
    private DomainJpaRepository domainJpaRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("GET /api/domains는 도메인 목록을 id 오름차순으로 반환한다")
    void getsDomains() throws Exception {
        DomainJpaEntity recruitment = domainJpaRepository.save(new DomainJpaEntity("recruitment"));
        DomainJpaEntity estate = domainJpaRepository.save(new DomainJpaEntity("real-estate"));

        HttpResponse<String> response = get("/api/domains");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "[{\"id\":%d,\"name\":\"recruitment\"},{\"id\":%d,\"name\":\"real-estate\"}]"
                        .formatted(recruitment.getId(), estate.getId())
        );
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
