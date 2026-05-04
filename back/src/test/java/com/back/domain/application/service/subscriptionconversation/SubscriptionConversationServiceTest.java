package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.back.domain.adapter.out.persistence.subscriptionconversation.SubscriptionConversationJpaEntity;
import com.back.domain.adapter.out.persistence.subscriptionconversation.SubscriptionConversationJpaRepository;
import com.back.domain.adapter.out.persistence.subscriptionconversation.SubscriptionMonitoringConfigJpaEntity;
import com.back.domain.adapter.out.persistence.subscriptionconversation.SubscriptionMonitoringConfigJpaRepository;
import com.back.domain.application.command.ContinueParseCommand;
import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.command.ParseTaskCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import com.back.domain.application.port.in.ParseTaskUseCase;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.NormalizeSubscriptionDraftPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.subscription.SubscriptionConversationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Application: subscription conversation service")
class SubscriptionConversationServiceTest {

    private final SubscriptionConversationJpaRepository conversationRepository =
            mock(SubscriptionConversationJpaRepository.class);
    private final SubscriptionMonitoringConfigJpaRepository monitoringConfigRepository =
            mock(SubscriptionMonitoringConfigJpaRepository.class);
    private final FakeParseTaskUseCase parseTaskUseCase = new FakeParseTaskUseCase();
    private final FakeCreateSubscriptionUseCase createSubscriptionUseCase = new FakeCreateSubscriptionUseCase();
    private final LoadDomainPort loadDomainPort = new FakeLoadDomainPort();
    private final LoadNotificationEndpointPort loadNotificationEndpointPort =
            (userId, channel) -> Optional.empty();

    @Test
    @DisplayName("new message parses with authenticated user id and asks missing channel")
    void newMessageParsesWithAuthenticatedUserId() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(realEstateTask(false)));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "강남구 아파트 매매 실거래가 알려줘",
                null
        );

        assertThat(parseTaskUseCase.receivedUserId).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.actions()).extracting(SubscriptionConversationService.ActionOption::type)
                .containsOnly("SELECT_CHANNEL");
    }

    @Test
    @DisplayName("parser needsConfirmation question is surfaced and continued with parseSessionId")
    void continuesParserConfirmationSession() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"condition\":\"10% 이상 하락\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "몇 % 이상 변동 시 알려드릴까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(savedConversation.getId(), 1L))
                .thenReturn(Optional.of(savedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "",
                "",
                "",
                "5% 이상",
                "",
                "",
                "api",
                "",
                List.of(),
                0.8,
                false,
                ""
        )));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                savedConversation.getId(),
                "상승 기준으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.receivedContinueSessionId).isEqualTo("parse-1");
        assertThat(response.conversationId()).isEqualTo(savedConversation.getId());
    }

    @Test
    @DisplayName("follow-up parser result keeps previously selected channel and internal check schedule")
    void followUpKeepsPreviousCadenceAndChannel() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"condition\":\"10% 이상 하락\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "기준을 조금 더 알려주세요.",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(savedConversation.getId(), 1L))
                .thenReturn(Optional.of(savedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(realEstateTask(false)));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        createSubscriptionUseCase.result = new SubscriptionResult(
                "sub-1",
                1L,
                1L,
                "강남구 아파트 매매 실거래가",
                true,
                LocalDateTime.now(),
                "schedule-1",
                "0 0 9 * * *",
                LocalDateTime.now().plusDays(1)
        );
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                savedConversation.getId(),
                "5% 이상",
                null
        );

        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(savedConversation.getDraftQuery()).isEqualTo("강남구 아파트 매매 실거래가");
        assertThat(savedConversation.getDraftDomainName()).isEqualTo("real-estate");
        assertThat(savedConversation.getDraftCronExpr()).isEqualTo("0 0 * * * *");
        assertThat(savedConversation.getDraftNotificationChannel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(savedConversation.getDraftMonitoringParams())
                .contains("\"conditionThreshold\":\"5\"")
                .contains("\"conditionDirection\":\"ANY\"")
                .contains("\"conditionUnit\":\"PERCENT\"");
    }

    @Test
    @DisplayName("percent condition answer keeps deal type confirmation before channel")
    void percentConditionAnswerKeepsDealTypeBeforeChannel() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "안산시 집값",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"안산시\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                null,
                null,
                null,
                "집값 변동 시 알림을 받으실 건가요? 몇 % 이상 변동 시 알려드릴까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(savedConversation.getId(), 1L))
                .thenReturn(Optional.of(savedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                savedConversation.getId(),
                "13",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("매매");
        assertThat(response.actions()).isEmpty();
        assertThat(savedConversation.getDraftCronExpr()).isEqualTo("0 0 * * * *");
        assertThat(savedConversation.getDraftMonitoringParams())
                .contains("\"conditionThreshold\":\"13\"")
                .contains("\"conditionDirection\":\"ANY\"")
                .contains("\"conditionUnit\":\"PERCENT\"");
    }

    @Test
    @DisplayName("percent condition answer keeps ambiguous apartment deal type unresolved")
    void percentConditionAnswerKeepsAmbiguousApartmentDealTypeUnresolved() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                null,
                null,
                null,
                "강남구 아파트 시세 변동을 어떤 조건으로 모니터링할까요? 예: 시세 변동률, 특정 가격대 등",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(savedConversation.getId(), 1L))
                .thenReturn(Optional.of(savedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(realEstateTask(false)));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                savedConversation.getId(),
                "3% 상승",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("매매");
        assertThat(response.actions()).isEmpty();
        assertThat(savedConversation.getDraftCronExpr()).isEqualTo("0 0 * * * *");
        assertThat(savedConversation.getDraftMonitoringParams())
                .contains("\"conditionThreshold\":\"3\"")
                .contains("\"conditionDirection\":\"UP\"")
                .contains("\"conditionUnit\":\"PERCENT\"");
    }

    @Test
    @DisplayName("percent condition answer keeps the current ambiguous conversation even when MCP tool is not resolved yet")
    void percentConditionAnswerKeepsAmbiguousConversationWithoutResolvedTool() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "안산 집값",
                1L,
                "real-estate",
                "apartment_trade_price",
                null,
                "{\"region\":\"안산\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                null,
                null,
                null,
                "어떤 변동 조건이 발생했을 때 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이하 등",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(savedConversation.getId(), 1L))
                .thenReturn(Optional.of(savedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-new", List.of(new ParsedTask(
                "reject",
                "기타",
                "5% 상승",
                "",
                "",
                "",
                "",
                "5% 상승",
                List.of(),
                0.1,
                false,
                ""
        )));
        SubscriptionConversationService service = service(
                loadNotificationEndpointPort,
                new FakeLoadMcpToolPort(null, null)
        );

        SubscriptionConversationService.Response response = service.handle(
                1L,
                savedConversation.getId(),
                "5% 상승",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isZero();
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("매매");
        assertThat(response.actions()).isEmpty();
        assertThat(savedConversation.getDraftCronExpr()).isEqualTo("0 0 * * * *");
        assertThat(savedConversation.getDraftMonitoringParams())
                .contains("\"conditionThreshold\":\"5\"")
                .contains("\"conditionDirection\":\"UP\"")
                .contains("\"conditionUnit\":\"PERCENT\"");
    }

    @Test
    @DisplayName("needsConfirmation parser result asks the parser question without quick actions")
    void asksParserConfirmationQuestion() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(realEstateTask(true)));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "강남구 아파트 매매 실거래가 알려줘",
                null
        );

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("몇 % 이상");
        assertThat(response.actions()).isEmpty();
    }

    @Test
    @DisplayName("planned domain does not create subscription")
    void plannedDomainDoesNotCreateSubscription() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "create",
                "채용",
                "카카오 백엔드 채용공고",
                "경력 3년 이하",
                "0 * * * *",
                "email",
                "crawl",
                "카카오 백엔드 채용공고",
                List.of(),
                0.9,
                false,
                ""
        )));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "카카오 채용공고 이메일로 매시간 알려줘",
                null
        );

        assertThat(response.assistantMessage()).contains("준비 중");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
    }

    // draftUsesStoredMcpTool 테스트 제거:
    // withStoredMcpTool()이 Spring AI 위임으로 제거됨 → toolName은 더 이상 저장되지 않음

    @Test
    @DisplayName("explicit unconnected DM channel asks for connection before confirmation")
    void explicitUnconnectedDmChannelAsksForConnectionBeforeConfirmation() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(realEstateTask(false)));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "강남구 아파트 매매 실거래가를 매일 아침 Telegram으로 알려줘",
                null
        );

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).isEqualTo("Telegram 연결이 필요합니다.");
        assertThat(response.actions()).extracting(SubscriptionConversationService.ActionOption::type)
                .contains("SELECT_CHANNEL");
    }

    @Test
    @DisplayName("ambiguous apartment change request asks for a deal type instead of becoming ready")
    void ambiguousApartmentChangeRequestAsksForDealType() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 변경",
                "5% 이상 상승",
                "0 9 * * *",
                "telegram",
                "api",
                "강남구 아파트 변경",
                List.of(),
                0.9,
                false,
                ""
        )));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "강남구 아파트 변경 텔레그램으로 매일 오전 9시에 알려줘",
                null
        );

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("매매");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
        verify(monitoringConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("generic apartment price request asks for deal type before condition")
    void genericApartmentPriceRequestAsksForDealTypeBeforeCondition() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 가격",
                "",
                "",
                "",
                "api",
                "강남구 아파트 가격",
                List.of(),
                0.9,
                false,
                ""
        )));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "강남구 아파트 가격",
                null
        );

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("매매");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
        verify(monitoringConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("pending deal type confirmation accepts short trade answer without AI continuation")
    void pendingDealTypeConfirmationAcceptsShortTradeAnswer() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "강남구 집값",
                1L,
                "real-estate",
                "apartment_trade_price",
                null,
                "{" +
                        "\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"," +
                        "\"region\":\"강남구\"," +
                        "\"pendingDealTypeConfirmation\":\"true\"," +
                        "\"conditionMetric\":\"AVG_PRICE\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"5\"," +
                        "\"conditionUnit\":\"PERCENT\"" +
                        "}",
                "0 0 9 * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "현재는 아파트 매매 실거래가 알림만 만들 수 있어요. 매매 실거래가 알림으로 만들까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(savedConversation.getId(), 1L))
                .thenReturn(Optional.of(savedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(realEstateTask(false)));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                savedConversation.getId(),
                "매매로 해줘",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().query()).isEqualTo("강남구 아파트 매매 실거래가");
        assertThat(response.draft().monitoringParams()).doesNotContainKey("pendingDealTypeConfirmation");
    }

    @Test
    @DisplayName("unsupported draft starts a new parse for the next free text message")
    void unsupportedDraftStartsNewParseForNextMessage() {
        SubscriptionConversationJpaEntity unsupportedConversation = new SubscriptionConversationJpaEntity(1L);
        unsupportedConversation.updateParsedDraft(
                "parse-unsupported",
                "안산시",
                null,
                "기타",
                "reject",
                null,
                "{}",
                null,
                null,
                null,
                "지원하지 않는 요청이에요.",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(unsupportedConversation.getId(), 1L))
                .thenReturn(Optional.of(unsupportedConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-new", List.of(realEstateTask(false)));
        parseTaskUseCase.continueResult = new ParseResult("parse-unsupported", List.of(new ParsedTask(
                "reject",
                "기타",
                "강남구 아파트",
                "지원하지 않는 도메인",
                "",
                "",
                "",
                "강남구 아파트",
                List.of(),
                0.1,
                false,
                ""
        )));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                unsupportedConversation.getId(),
                "강남구 아파트 매매 실거래가를 매일 아침 Telegram으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.assistantMessage()).isNotEqualTo("지원하지 않는 요청이에요.");
    }

    @Test
    @DisplayName("confirmed ready draft creates subscription and saves monitoring config")
    void confirmCreatesSubscriptionAndMonitoringConfig() {
        SubscriptionConversationJpaEntity readyConversation = new SubscriptionConversationJpaEntity(1L);
        readyConversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"condition\":\"10% 이상 하락\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "아래 내용으로 알림을 시작할까요?",
                SubscriptionConversationStatus.READY_FOR_CONFIRMATION
        );
        when(conversationRepository.findByIdAndUserId(readyConversation.getId(), 1L))
                .thenReturn(Optional.of(readyConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(monitoringConfigRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        createSubscriptionUseCase.result = new SubscriptionResult(
                "sub-1",
                1L,
                1L,
                "강남구 아파트 매매 실거래가",
                true,
                LocalDateTime.now(),
                "schedule-1",
                "0 0 9 * * *",
                LocalDateTime.now().plusDays(1)
        );
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                readyConversation.getId(),
                null,
                new SubscriptionConversationService.ActionRequest("CONFIRM_SUBSCRIPTION", "confirm")
        );

        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(createSubscriptionUseCase.receivedCommand.notificationChannel())
                .isEqualTo(NotificationChannel.TELEGRAM_DM);
        ArgumentCaptor<SubscriptionMonitoringConfigJpaEntity> captor =
                ArgumentCaptor.forClass(SubscriptionMonitoringConfigJpaEntity.class);
        verify(monitoringConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getToolName()).isEqualTo("search_house_price");
    }

    @Test
    @DisplayName("ready draft without condition asks for condition instead of creating subscription")
    void readyDraftWithoutConditionAsksForConditionInsteadOfCreatingSubscription() {
        SubscriptionConversationJpaEntity readyConversation = new SubscriptionConversationJpaEntity(1L);
        readyConversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "아래 내용으로 알림을 시작할까요?",
                SubscriptionConversationStatus.READY_FOR_CONFIRMATION
        );
        when(conversationRepository.findByIdAndUserId(readyConversation.getId(), 1L))
                .thenReturn(Optional.of(readyConversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                readyConversation.getId(),
                null,
                new SubscriptionConversationService.ActionRequest("CONFIRM_SUBSCRIPTION", "confirm")
        );

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("가격 변동 조건");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
    }

    @Test
    @DisplayName("selecting a connected channel advances the draft to confirmation")
    void selectingConnectedChannelAdvancesToConfirmation() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"condition\":\"10% 이상 하락\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                null,
                null,
                "알림을 받을 채널을 선택해 주세요.",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                null,
                new SubscriptionConversationService.ActionRequest("SELECT_CHANNEL", "TELEGRAM_DM")
        );

        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(conversation.getDraftNotificationChannel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        verify(monitoringConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("typed channel answer is handled locally instead of reparsing as a new request")
    void typedChannelAnswerIsHandledLocally() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"강남구\",\"condition\":\"10% 이상 하락\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                null,
                null,
                "알림을 받을 채널을 선택해 주세요. Telegram, Discord, Email 중 무엇으로 받을까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "reject",
                "기타",
                "텔레그램",
                "지원하지 않는 도메인",
                "",
                "",
                "",
                "텔레그램",
                List.of(),
                0.1,
                false,
                ""
        )));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                "텔레그램",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).isEqualTo("Telegram 연결이 필요합니다.");
        assertThat(response.actions()).extracting(SubscriptionConversationService.ActionOption::type)
                .contains("SELECT_CHANNEL");
    }

    // missingMcpToolReportsServerSetupProblem 테스트 제거:
    // mcpTool missing 체크가 Spring AI 위임으로 제거됨 → toolName null은 더 이상 에러 조건이 아님

    private SubscriptionConversationService service(LoadNotificationEndpointPort endpointPort) {
        return service(endpointPort, new FakeLoadMcpToolPort(mcpTool("search_house_price"), mcpTool("search_house_price")));
    }

    private SubscriptionConversationService service(
            LoadNotificationEndpointPort endpointPort,
            LoadMcpToolPort loadMcpToolPort
    ) {
        return new SubscriptionConversationService(
                parseTaskUseCase,
                new ParsedTaskNormalizer(new FakeNormalizeSubscriptionDraftPort()),
                createSubscriptionUseCase,
                loadDomainPort,
                loadMcpToolPort,
                endpointPort,
                conversationRepository,
                monitoringConfigRepository,
                new ObjectMapper()
        );
    }

    private static McpTool mcpTool(String name) {
        Domain domain = new Domain(1L, "real-estate");
        return new McpTool(
                1L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                name,
                "부동산 실거래가 조회",
                "{}"
        );
    }

    private ParsedTask realEstateTask(boolean needsConfirmation) {
        return new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                needsConfirmation ? "" : "5% 이상",
                "0 9 * * *",
                "telegram",
                "api",
                "강남구 아파트 매매 실거래가 변동",
                List.of(),
                0.9,
                needsConfirmation,
                needsConfirmation ? "몇 % 이상 변동 시 알려드릴까요?" : ""
        );
    }

    private static class FakeNormalizeSubscriptionDraftPort implements NormalizeSubscriptionDraftPort {
        @Override
        public Optional<DomainNormalizedSubscriptionDraft> normalize(
                ParsedTask task,
                String userMessage,
                SubscriptionDraft previousDraft
        ) {
            String domainName = canonicalDomainName(task.domainName());
            if (isBlank(domainName) && previousDraft != null) {
                domainName = previousDraft.domainName();
            }
            boolean canReusePrevious = previousDraft != null && domainName.equals(previousDraft.domainName());
            String query = !isBlank(task.query()) ? task.query() : canReusePrevious ? previousDraft.query() : task.query();
            String parseIntent = !isBlank(task.intent()) ? task.intent() : canReusePrevious ? "create" : "";

            if (isBlank(domainName) || "reject".equals(parseIntent)) {
                return Optional.of(new DomainNormalizedSubscriptionDraft(
                        query,
                        domainName,
                        parseIntent,
                        null,
                        Map.of(),
                        List.of("unsupportedDomain"),
                        "지원하지 않는 요청이에요.",
                        task.confidence()
                ));
            }
            if (!"create".equals(parseIntent)) {
                return Optional.of(new DomainNormalizedSubscriptionDraft(
                        query,
                        domainName,
                        parseIntent,
                        null,
                        Map.of(),
                        List.of("unsupportedIntent"),
                        "알림 수정과 삭제는 아직 채팅 생성 플로우에서 처리하지 않아요.",
                        task.confidence()
                ));
            }
            if (!"real-estate".equals(domainName)) {
                return Optional.of(new DomainNormalizedSubscriptionDraft(
                        query,
                        domainName,
                        null,
                        null,
                        Map.of(),
                        List.of("unsupportedCapability"),
                        "채용 알림은 준비 중이에요. 현재는 부동산 아파트 매매 실거래가 알림만 만들 수 있어요.",
                        task.confidence()
                ));
            }

            Map<String, String> params = new LinkedHashMap<>();
            params.put("dealYmdPolicy", "LATEST_AVAILABLE_MONTH");
            if (canReusePrevious) {
                params.putAll(previousDraft.monitoringParams());
            }
            if (containsAny(query, "강남구", "강남")) {
                params.put("region", "강남구");
            }
            if (containsAny(query, "안산시", "안산")) {
                params.put("region", "안산시");
            }
            params.putAll(conditionParams(task.condition()));

            List<String> missing = new java.util.ArrayList<>();
            if (!params.containsKey("region")) {
                missing.add("region");
            }
            if (StructuredCondition.fromParameters(params).isEmpty()) {
                missing.add("condition");
            }
            if (requiresExplicitApartmentDealType(userMessage, query, task.target())) {
                missing.add("dealType");
                params.put("pendingDealTypeConfirmation", "true");
            }

            return Optional.of(new DomainNormalizedSubscriptionDraft(
                    query,
                    "real-estate",
                    "apartment_trade_price",
                    "search_house_price",
                    params,
                    missing,
                    questionForMissing(missing),
                    task.confidence()
            ));
        }

        private static Map<String, String> conditionParams(String condition) {
            if (isBlank(condition)) {
                return Map.of();
            }
            String threshold = condition.replaceAll("[^0-9.]", "");
            if (threshold.isBlank()) {
                return Map.of();
            }
            return Map.of(
                    "conditionMetric", "AVG_PRICE",
                    "conditionDirection", condition.contains("상승") || condition.contains("오르") ? "UP" : "ANY",
                    "conditionOperator", condition.contains("초과") ? "GT" : "GTE",
                    "conditionThreshold", threshold,
                    "conditionUnit", condition.contains("만원") ? "MANWON" : "PERCENT"
            );
        }

        private static String canonicalDomainName(String value) {
            return switch (value == null ? "" : value.trim()) {
                case "부동산", "real-estate" -> "real-estate";
                case "법률", "법률/규제", "law-regulation" -> "law-regulation";
                case "채용", "recruitment" -> "recruitment";
                case "경매", "경매/희소매물", "auction" -> "auction";
                default -> value == null ? "" : value.trim();
            };
        }

        private static boolean requiresExplicitApartmentDealType(String userMessage, String query, String target) {
            String source = !isBlank(userMessage) ? userMessage : query;
            String text = (source == null ? "" : source).toLowerCase();
            if (text.contains("전월세") || text.contains("전세") || text.contains("월세") || text.contains("매매")) {
                return false;
            }
            return text.contains("아파트")
                    || text.contains("가격")
                    || text.contains("시세")
                    || text.contains("집값")
                    || text.contains("실거래가")
                    || text.contains("변경")
                    || text.contains("변동");
        }

        private static String questionForMissing(List<String> missing) {
            if (missing.contains("region")) {
                return "어느 지역의 아파트 매매 실거래가를 확인할까요?";
            }
            if (missing.contains("dealType")) {
                return "아파트 가격은 매매/전세/월세 중 어떤 기준인가요? 현재는 매매 실거래가 알림만 만들 수 있어요.";
            }
            if (missing.contains("condition")) {
                return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등";
            }
            return "";
        }

        private static boolean containsAny(String value, String... candidates) {
            String text = value == null ? "" : value;
            for (String candidate : candidates) {
                if (text.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    private static class FakeParseTaskUseCase implements ParseTaskUseCase {
        ParseResult parseResult;
        ParseResult continueResult;
        Long receivedUserId;
        String receivedContinueSessionId;
        int parseCallCount;
        int continueCallCount;

        @Override
        public ParseResult parse(ParseTaskCommand command) {
            this.parseCallCount++;
            this.receivedUserId = command.userId();
            return parseResult;
        }

        @Override
        public ParseResult continueParse(ContinueParseCommand command) {
            this.continueCallCount++;
            this.receivedUserId = command.userId();
            this.receivedContinueSessionId = command.sessionId();
            return continueResult;
        }
    }

    private static class FakeCreateSubscriptionUseCase implements CreateSubscriptionUseCase {
        CreateSubscriptionCommand receivedCommand;
        SubscriptionResult result;

        @Override
        public SubscriptionResult createForUser(Long userId, CreateSubscriptionCommand command) {
            this.receivedCommand = command;
            return result;
        }
    }

    private static class FakeLoadMcpToolPort implements LoadMcpToolPort {
        private final McpTool defaultTool;
        private final McpTool namedTool;

        private FakeLoadMcpToolPort(McpTool defaultTool, McpTool namedTool) {
            this.defaultTool = defaultTool;
            this.namedTool = namedTool;
        }

        @Override
        public Optional<McpTool> loadByDomainId(Long domainId) {
            return Optional.ofNullable(defaultTool);
        }

        @Override
        public Optional<McpTool> loadByDomainIdAndName(Long domainId, String toolName) {
            return Optional.ofNullable(namedTool)
                    .filter(tool -> tool.name().equals(toolName));
        }
    }

    private static class FakeLoadDomainPort implements LoadDomainPort {
        @Override
        public Optional<Domain> loadById(Long domainId) {
            return loadAll().stream()
                    .filter(domain -> domain.id().equals(domainId))
                    .findFirst();
        }

        @Override
        public List<Domain> loadAll() {
            return List.of(
                    new Domain(1L, "real-estate"),
                    new Domain(2L, "law-regulation"),
                    new Domain(3L, "recruitment"),
                    new Domain(4L, "auction")
            );
        }
    }
}
