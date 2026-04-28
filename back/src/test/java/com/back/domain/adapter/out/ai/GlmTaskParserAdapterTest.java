package com.back.domain.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.back.domain.application.result.ParsedTask;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GlmTaskParserAdapterTest {

    @Test
    void parsePostsOpenAiCompatibleChatCompletionsRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GlmTaskParserAdapter adapter = new GlmTaskParserAdapter(
                builder,
                "https://api.z.ai/api/coding/paas/v4",
                "parser-key",
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(adapter, "model", "glm-4.5");
        ReflectionTestUtils.setField(adapter, "maxTokens", 2048);
        ReflectionTestUtils.setField(adapter, "temperature", 0.3);

        server.expect(requestTo("https://api.z.ai/api/coding/paas/v4/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer parser-key"))
                .andExpect(content().string(containsString("\"max_tokens\":2048")))
                .andRespond(withSuccess("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "[{\\"intent\\":\\"create\\",\\"domain_name\\":\\"부동산\\",\\"query\\":\\"안산시 본오동 집값\\",\\"condition\\":\\"\\",\\"cron_expr\\":\\"\\",\\"channel\\":\\"\\",\\"api_type\\":\\"crawl\\",\\"metadata\\":{\\"target\\":\\"안산시 본오동\\",\\"urls\\":[],\\"confidence\\":0.8,\\"needs_confirmation\\":true,\\"confirmation_question\\":\\"주기를 선택해 주세요.\\"}}]"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        List<ParsedTask> result = adapter.parse("안산시 본오동 집값");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().domainName()).isEqualTo("부동산");
        assertThat(result.getFirst().target()).isEqualTo("안산시 본오동");
        server.verify();
    }

    @Test
    void parseRejectsInvalidEnumValuesFromAiResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GlmTaskParserAdapter adapter = new GlmTaskParserAdapter(
                builder,
                "https://api.z.ai/api/coding/paas/v4",
                "parser-key",
                new ObjectMapper()
        );

        server.expect(requestTo("https://api.z.ai/api/coding/paas/v4/chat/completions"))
                .andRespond(withSuccess("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "[{\\"intent\\":\\"subscribe\\",\\"domain_name\\":\\"부동산\\",\\"query\\":\\"강남구 집값\\",\\"condition\\":\\"5% 이상 상승\\",\\"cron_expr\\":\\"0 9 * * *\\",\\"channel\\":\\"telegram\\",\\"api_type\\":\\"api\\",\\"metadata\\":{\\"target\\":\\"강남구\\",\\"urls\\":[],\\"confidence\\":0.8,\\"needs_confirmation\\":false,\\"confirmation_question\\":\\"\\"}}]"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.parse("강남구 집값"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PARSE_FAILED);
    }

    @Test
    void parseRejectsMissingMetadataFromAiResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GlmTaskParserAdapter adapter = new GlmTaskParserAdapter(
                builder,
                "https://api.z.ai/api/coding/paas/v4",
                "parser-key",
                new ObjectMapper()
        );

        server.expect(requestTo("https://api.z.ai/api/coding/paas/v4/chat/completions"))
                .andRespond(withSuccess("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "[{\\"intent\\":\\"create\\",\\"domain_name\\":\\"부동산\\",\\"query\\":\\"강남구 집값\\",\\"condition\\":\\"5% 이상 상승\\",\\"cron_expr\\":\\"0 9 * * *\\",\\"channel\\":\\"telegram\\",\\"api_type\\":\\"api\\"}]"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.parse("강남구 집값"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PARSE_FAILED);
    }
}
