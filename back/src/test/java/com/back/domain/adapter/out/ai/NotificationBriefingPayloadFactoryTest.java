package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.ai.McpToolExecutionRecorder.Execution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Adapter: Notification briefing payload factory")
class NotificationBriefingPayloadFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("부동산 compare 결과로 send_notification channel-v1 briefing을 결정론적으로 보강한다")
    void enrichesRealEstateSendNotificationInputFromCompareResult() throws Exception {
        String rawInput = """
                {
                  "input": {
                    "subscriptionId": "sub-1",
                    "notificationChannel": "EMAIL",
                    "notificationTarget": "user@example.com",
                    "title": "강남구 아파트 매매가격 변동 알림",
                    "message": "강남구 아파트 매매가격에 변화가 감지되었습니다.",
                    "metadata": {
                      "briefingContractVersion": "channel-v1",
                      "briefing": {
                        "domain": "real-estate",
                        "title": "강남구 아파트 매매가격 변동 알림",
                        "summary": "강남구 아파트 매매가격에 변화가 감지되었습니다.",
                        "changes": [
                          {
                            "label": "평균 매매가",
                            "baseline": "29억원",
                            "current": "29억 4,209만원",
                            "change_rate": "1.45% 상승"
                          }
                        ],
                        "watchInfo": {
                          "region": "강남구",
                          "dealPeriod": "202604"
                        },
                        "interpretation": "표본 수와 거래 구성을 함께 확인하세요."
                      }
                    }
                  }
                }
                """;
        List<Execution> executions = List.of(
                new Execution("search_house_price", """
                        {"input":{"region":"강남구","deal_ymd":"202604"}}
                        """, """
                        {"structured":{"summary":{"count":100,"avg_deal_amount":294209,"min_deal_amount":13900,"max_deal_amount":900000},"query":{"region":"강남구","deal_ymd":"202604"}},"metadata":{"tool_name":"search_house_price"}}
                        """, false),
                new Execution("compare_subscription_change", """
                        {"input":{"subscriptionId":"sub-1","domain":"real-estate","query":"강남구 아파트 매매가","params":{"region":"강남구","deal_ymd":"202604","conditionMetric":"AVG_PRICE","conditionDirection":"ANY","conditionOperator":"GTE","conditionThreshold":"0","conditionUnit":"PERCENT"}}}
                        """, """
                        {"structured":{"baseline_initialized":false,"changed":true,"subscriptionId":"sub-1","domain":"real-estate","baseline_summary":{"count":100,"avg_deal_amount":290000,"min_deal_amount":13000,"max_deal_amount":880000},"current_summary":{"count":100,"avg_deal_amount":294209,"min_deal_amount":13900,"max_deal_amount":900000},"diffs":[{"field":"avg_deal_amount","baseline_value":290000,"current_value":294209,"delta":4209,"change_rate":1.45,"direction":"increase"}],"briefing_facts":["평균 매매가가 29억원에서 29억 4,209만원으로 증가했습니다.","평균 매매가 변화율은 1.45%입니다."],"condition_satisfied":true,"requires_ai_analysis":true}}
                        """, false)
        );

        String enriched = NotificationBriefingPayloadFactory.enrichSendNotificationInput(
                rawInput,
                executions,
                objectMapper
        );

        JsonNode input = objectMapper.readTree(enriched).path("input");
        JsonNode briefing = input.path("metadata").path("briefing");

        assertThat(input.path("metadata").path("briefingContractVersion").asText()).isEqualTo("channel-v1");
        assertThat(briefing.path("domain").asText()).isEqualTo("real-estate");
        assertThat(briefing.path("watchInfo").path("region").asText()).isEqualTo("강남구");
        assertThat(briefing.path("watchInfo").path("dealPeriod").asText()).isEqualTo("202604");
        assertThat(briefing.path("changes").findValuesAsText("label"))
                .contains("평균 매매가", "변화율", "거래 건수", "데이터 출처");
        briefing.path("changes").forEach(change -> assertThat(change.has("previous") && change.has("current")).isTrue());
        assertThat(briefing.path("changes").get(0).path("value").asText())
                .isEqualTo("29억원 → 29억 4,209만원 (+1.45%)");
        assertThat(briefing.path("changes").get(0).path("previous").asText()).isEqualTo("29억원");
        assertThat(briefing.path("changes").get(0).path("current").asText()).isEqualTo("29억 4,209만원");
        assertThat(briefing.path("changes").get(2).path("previous").asText()).isEqualTo("100건");
        assertThat(briefing.path("changes").get(2).path("current").asText()).isEqualTo("100건");
    }

    @Test
    @DisplayName("채용 compare 결과로 send_notification channel-v1 briefing을 결정론적으로 보강한다")
    void enrichesRecruitmentSendNotificationInputFromCompareResult() throws Exception {
        String rawInput = """
                {
                  "input": {
                    "subscriptionId": "sub-job-1",
                    "notificationChannel": "EMAIL",
                    "notificationTarget": "user@example.com",
                    "title": "전산직 채용 새 공고",
                    "message": "새로운 전산직 채용 공고가 감지되었습니다.",
                    "metadata": {
                      "briefingContractVersion": "channel-v1",
                      "briefing": {
                        "domain": "recruitment",
                        "title": "전산직 채용 새 공고",
                        "summary": "새로운 전산직 채용 공고가 1건 올라왔습니다.",
                        "interpretation": "마감일과 지원 자격을 확인하세요."
                      }
                    }
                  }
                }
                """;
        List<Execution> executions = List.of(
                new Execution("compare_subscription_change", """
                        {"input":{"subscriptionId":"sub-job-1","domain":"recruitment","query":"전산직 채용 공고","params":{"keyword":"전산직","conditionMetric":"COUNT","conditionDirection":"UP","conditionOperator":"GTE","conditionThreshold":"1","conditionUnit":"COUNT","dataToolName":"search_public_job"}}}
                        """, """
                        {"structured":{"baseline_initialized":false,"changed":true,"subscriptionId":"sub-job-1","domain":"recruitment","baseline_summary":{"count":3,"ongoing_count":3,"posting_ids":["public_job:old"],"ongoing_posting_ids":["public_job:old"]},"current_summary":{"count":4,"ongoing_count":4,"posting_ids":["public_job:old","public_job:new"],"ongoing_posting_ids":["public_job:old","public_job:new"]},"diffs":[{"field":"added_count","baseline_value":0,"current_value":1,"delta":1,"direction":"increase"},{"field":"count","baseline_value":3,"current_value":4,"delta":1,"change_rate":33.33,"direction":"increase"}],"briefing_facts":["신규 공고 수가 0건에서 1건으로 1건 증가했습니다."],"briefing_postings_by_source":{"public_job":[{"posting_id":"public_job:new","title":"국토연구원 전산직 채용","url":"https://example.com/jobs/new"}],"worknet_job":[]},"condition_satisfied":true,"requires_ai_analysis":true}}
                        """, false)
        );

        String enriched = NotificationBriefingPayloadFactory.enrichSendNotificationInput(
                rawInput,
                executions,
                objectMapper
        );

        JsonNode input = objectMapper.readTree(enriched).path("input");
        JsonNode briefing = input.path("metadata").path("briefing");

        assertThat(input.path("metadata").path("briefingContractVersion").asText()).isEqualTo("channel-v1");
        assertThat(briefing.path("domain").asText()).isEqualTo("recruitment");
        assertThat(briefing.path("watchInfo").path("keyword").asText()).isEqualTo("전산직");
        assertThat(briefing.path("watchInfo").path("dataSource").asText()).isEqualTo("공공채용");
        assertThat(briefing.path("changes").findValuesAsText("label"))
                .contains("신규 공고 수", "전체 공고 수", "데이터 출처");
        briefing.path("changes").forEach(change -> assertThat(change.has("label")
                && change.has("value")
                && change.has("previous")
                && change.has("current")).isTrue());
        assertThat(briefing.path("sources").get(0).path("label").asText()).isEqualTo("국토연구원 전산직 채용");
        assertThat(briefing.path("sources").get(0).path("url").asText()).isEqualTo("https://example.com/jobs/new");
    }

    @Test
    @DisplayName("채용 compare 결과가 MCP text wrapper로 감싸져도 source와 change를 보강한다")
    void enrichesRecruitmentSendNotificationInputFromMcpTextWrapper() throws Exception {
        String rawInput = """
                {
                  "input": {
                    "subscriptionId": "sub-job-wrapper",
                    "notificationChannel": "EMAIL",
                    "notificationTarget": "user@example.com",
                    "title": "공공기관 채용 새 공고",
                    "message": "새로운 채용 공고가 감지되었습니다.",
                    "metadata": {
                      "briefingContractVersion": "channel-v1",
                      "briefing": {
                        "domain": "recruitment",
                        "title": "공공기관 채용 새 공고",
                        "summary": "AI가 만든 요약입니다.",
                        "changes": [
                          {"label": "AI 누락 change", "value": "2건"}
                        ],
                        "sources": [],
                        "interpretation": "마감일과 지원 자격을 확인하세요."
                      }
                    }
                  }
                }
                """;
        String comparePayload = """
                {
                  "structured": {
                    "baseline_initialized": false,
                    "changed": true,
                    "subscriptionId": "sub-job-wrapper",
                    "domain": "recruitment",
                    "baseline_summary": {
                      "count": 3,
                      "ongoing_count": 2,
                      "posting_ids": ["public_job:old"],
                      "ongoing_posting_ids": ["public_job:old"]
                    },
                    "current_summary": {
                      "count": 5,
                      "ongoing_count": 4,
                      "posting_ids": ["public_job:old", "public_job:new-1", "public_job:new-2"],
                      "ongoing_posting_ids": ["public_job:old", "public_job:new-1", "public_job:new-2"]
                    },
                    "diffs": [
                      {"field": "added_count", "baseline_value": 0, "current_value": 2, "delta": 2, "direction": "increase"},
                      {"field": "ongoing_added_count", "baseline_value": 0, "current_value": 2, "delta": 2, "direction": "increase"},
                      {"field": "count", "baseline_value": 3, "current_value": 5, "delta": 2, "change_rate": 66.67, "direction": "increase"}
                    ],
                    "briefing_postings_by_source": {
                      "public_job": [
                        {"posting_id": "public_job:new-1", "title": "국토연구원 전산직 채용", "url": "https://example.com/jobs/new-1"},
                        {"posting_id": "public_job:new-2", "title": "한국도로공사 백엔드 채용", "url": "https://example.com/jobs/new-2"}
                      ],
                      "worknet_job": []
                    },
                    "condition_satisfied": true,
                    "requires_ai_analysis": true
                  }
                }
                """;
        List<Execution> executions = List.of(
                new Execution("compare_subscription_change", """
                        {"input":{"subscriptionId":"sub-job-wrapper","domain":"recruitment","query":"공공기관 채용 새 공고","params":{"conditionMetric":"COUNT","conditionDirection":"UP","conditionOperator":"GTE","conditionThreshold":"1","conditionUnit":"COUNT","dataToolName":"search_public_job"}}}
                        """, objectMapper.writeValueAsString(List.of(Map.of("text", comparePayload))), false)
        );

        String enriched = NotificationBriefingPayloadFactory.enrichSendNotificationInput(
                rawInput,
                executions,
                objectMapper
        );

        JsonNode briefing = objectMapper.readTree(enriched).path("input").path("metadata").path("briefing");

        assertThat(briefing.path("sources")).hasSize(2);
        assertThat(briefing.path("sources").get(0).path("label").asText()).isEqualTo("국토연구원 전산직 채용");
        assertThat(briefing.path("sources").get(0).path("url").asText()).isEqualTo("https://example.com/jobs/new-1");
        assertThat(briefing.path("changes").findValuesAsText("label"))
                .contains("신규 공고 수", "신규 진행중 공고 수", "전체 공고 수", "데이터 출처");
        briefing.path("changes").forEach(change -> assertThat(change.has("label")
                && change.has("value")
                && change.has("previous")
                && change.has("current")).isTrue());
    }

    @Test
    @DisplayName("채용 신규 공고 URL이 없으면 데이터 도구 source_url을 briefing source fallback으로 사용한다")
    void enrichesRecruitmentSourceFromDataToolSourceUrlWhenPostingUrlsAreMissing() throws Exception {
        String rawInput = """
                {
                  "input": {
                    "subscriptionId": "sub-job-source-fallback",
                    "notificationChannel": "EMAIL",
                    "notificationTarget": "user@example.com",
                    "title": "공공기관 채용 새 공고",
                    "message": "새로운 채용 공고가 감지되었습니다.",
                    "metadata": {
                      "briefingContractVersion": "channel-v1",
                      "briefing": {
                        "domain": "recruitment",
                        "title": "공공기관 채용 새 공고",
                        "summary": "새로운 채용 공고가 감지되었습니다.",
                        "interpretation": "마감일과 지원 자격을 확인하세요."
                      }
                    }
                  }
                }
                """;
        List<Execution> executions = List.of(
                new Execution("search_public_job", """
                        {"input":{"page_no":1,"num_of_rows":20,"ongoing_yn":"Y"}}
                        """, """
                        {"structured":{"summary":{"count":20,"ongoing_count":20},"query":{"page_no":1,"num_of_rows":20,"ongoing_yn":"Y"}},"source_url":"https://apis.data.go.kr/1051000/recruitment/list?serviceKey=***","metadata":{"tool_name":"search_public_job"}}
                        """, false),
                new Execution("compare_subscription_change", """
                        {"input":{"subscriptionId":"sub-job-source-fallback","domain":"recruitment","query":"공공기관 채용 새 공고","params":{"conditionMetric":"COUNT","conditionDirection":"UP","conditionOperator":"GTE","conditionThreshold":"1","conditionUnit":"COUNT","dataToolName":"search_public_job"}}}
                        """, """
                        {"structured":{"baseline_initialized":false,"changed":true,"subscriptionId":"sub-job-source-fallback","domain":"recruitment","baseline_summary":{"count":0,"ongoing_count":0,"posting_ids":[],"ongoing_posting_ids":[]},"current_summary":{"count":20,"ongoing_count":20,"posting_ids":["public_job:new"],"ongoing_posting_ids":["public_job:new"]},"diffs":[{"field":"added_count","baseline_value":0,"current_value":20,"delta":20,"direction":"increase"}],"briefing_postings_by_source":{"public_job":[{"posting_id":"public_job:new","title":"URL 없는 공공기관 채용","url":null}],"worknet_job":[]},"condition_satisfied":true,"requires_ai_analysis":true}}
                        """, false)
        );

        String enriched = NotificationBriefingPayloadFactory.enrichSendNotificationInput(
                rawInput,
                executions,
                objectMapper
        );

        JsonNode briefing = objectMapper.readTree(enriched).path("input").path("metadata").path("briefing");

        assertThat(briefing.path("sources")).hasSize(1);
        assertThat(briefing.path("sources").get(0).path("label").asText()).isEqualTo("공공채용");
        assertThat(briefing.path("sources").get(0).path("url").asText())
                .isEqualTo("https://apis.data.go.kr/1051000/recruitment/list?serviceKey=***");
    }
}
