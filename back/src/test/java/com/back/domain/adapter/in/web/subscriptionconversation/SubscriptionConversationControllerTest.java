package com.back.domain.adapter.in.web.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserSessionJpaRepository;
import com.back.domain.application.service.SessionTokenService;
import com.back.domain.application.service.subscriptionconversation.SubscriptionConversationService;
import com.back.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Web: subscription conversation API")
class SubscriptionConversationControllerTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserSessionJpaRepository sessionRepository;

    @Autowired
    private SessionTokenService sessionTokenService;

    @MockitoBean
    private SubscriptionConversationService conversationService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("unauthenticated request returns UNAUTHENTICATED")
    void rejectsUnauthenticatedRequest() throws Exception {
        HttpResponse<String> response = post("""
                {
                  "message": "강남구 아파트 매매 실거래가 알려줘"
                }
                """, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    @DisplayName("authenticated text request uses current user and returns actions")
    void handlesAuthenticatedTextRequest() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("chat-user@example.com", "채팅사용자"));
        String cookieHeader = createSession(user, "conversation-session");
        when(conversationService.handle(eq(user.getId()), eq(null), eq("강남구 아파트 매매 실거래가 알려줘"), eq(null)))
                .thenReturn(new SubscriptionConversationService.Response(
                        "conversation-1",
                        "NEEDS_INPUT",
                        "알림을 받을 채널을 선택해 주세요.",
                        null,
                        List.of(new SubscriptionConversationService.ActionOption(
                                "SELECT_CHANNEL",
                                "Telegram",
                                "TELEGRAM_DM",
                                false,
                                true
                        )),
                        null
                ));

        HttpResponse<String> response = post("""
                {
                  "message": "강남구 아파트 매매 실거래가 알려줘"
                }
                """, cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"NEEDS_INPUT\"");
        assertThat(response.body()).contains("\"type\":\"SELECT_CHANNEL\"");
        verify(conversationService).handle(user.getId(), null, "강남구 아파트 매매 실거래가 알려줘", null);
    }

    @Test
    @DisplayName("request body userId is ignored in favor of authenticated user")
    void ignoresBodyUserId() throws Exception {
        UserJpaEntity user = userJpaRepository.save(new UserJpaEntity("body-user@example.com", "채팅사용자"));
        String cookieHeader = createSession(user, "conversation-body-session");
        when(conversationService.handle(eq(user.getId()), eq(null), eq("강남구 알림"), eq(null)))
                .thenReturn(new SubscriptionConversationService.Response(
                        "conversation-1",
                        "NEEDS_INPUT",
                        "얼마나 자주 확인할까요?",
                        null,
                        List.of(),
                        null
                ));

        HttpResponse<String> response = post("""
                {
                  "userId": 999,
                  "message": "강남구 알림"
                }
                """, cookieHeader);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        verify(conversationService).handle(user.getId(), null, "강남구 알림", null);
    }

    private String createSession(UserJpaEntity user, String rawToken) {
        sessionRepository.save(new UserSessionJpaEntity(
                user,
                sessionTokenService.hash(rawToken),
                LocalDateTime.now().plusDays(7)
        ));
        return "SESSION=" + rawToken;
    }

    private HttpResponse<String> post(String body, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/subscription-conversations/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
