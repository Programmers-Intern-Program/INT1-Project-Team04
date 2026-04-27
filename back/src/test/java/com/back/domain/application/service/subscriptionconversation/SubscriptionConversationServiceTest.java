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
import java.util.List;
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
    @DisplayName("new message parses with authenticated user id and asks missing cadence/channel")
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
                .contains("SELECT_CADENCE", "SELECT_CHANNEL");
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
    @DisplayName("follow-up parser result keeps previously selected cadence and channel")
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
        assertThat(savedConversation.getDraftCronExpr()).isEqualTo("0 0 9 * * *");
        assertThat(savedConversation.getDraftNotificationChannel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(savedConversation.getDraftMonitoringParams()).contains("\"condition\":\"5% 이상\"");
    }

    @Test
    @DisplayName("percent condition answer completes parser question without continuing parse use case")
    void percentConditionAnswerCompletesParserQuestionLocally() {
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
        assertThat(response.assistantMessage()).isEqualTo("얼마나 자주 확인할까요?");
        assertThat(response.actions()).extracting(SubscriptionConversationService.ActionOption::type)
                .contains("SELECT_CADENCE", "SELECT_CHANNEL");
        assertThat(savedConversation.getDraftMonitoringParams()).contains("\"condition\":\"13% 이상 변동\"");
    }

    @Test
    @DisplayName("percent condition answer keeps the current conversation even when MCP tool is not resolved yet")
    void percentConditionAnswerKeepsConversationWithoutResolvedTool() {
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
        assertThat(response.assistantMessage()).isEqualTo("얼마나 자주 확인할까요?");
        assertThat(savedConversation.getDraftMonitoringParams()).contains("\"condition\":\"5% 이상 상승\"");
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

    @Test
    @DisplayName("new draft uses the MCP tool available from storage")
    void draftUsesStoredMcpTool() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(realEstateTask(false)));
        McpTool storedTool = mcpTool("search_house_price_v2");
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(
                connectedTelegram,
                new FakeLoadMcpToolPort(storedTool, null)
        );

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "강남구 아파트 매매 실거래가를 매일 아침 Telegram으로 알려줘",
                null
        );

        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().toolName()).isEqualTo("search_house_price_v2");
    }

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

    @Test
    @DisplayName("missing MCP tool is reported as server setup problem instead of generic missing input")
    void missingMcpToolReportsServerSetupProblem() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                null,
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
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(
                connectedTelegram,
                new FakeLoadMcpToolPort(null, null)
        );

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                null,
                new SubscriptionConversationService.ActionRequest("SELECT_CHANNEL", "TELEGRAM_DM")
        );

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("알림 도구 설정");
        assertThat(response.actions()).isEmpty();
    }

    private SubscriptionConversationService service(LoadNotificationEndpointPort endpointPort) {
        return service(endpointPort, new FakeLoadMcpToolPort(mcpTool("search_house_price"), mcpTool("search_house_price")));
    }

    private SubscriptionConversationService service(
            LoadNotificationEndpointPort endpointPort,
            LoadMcpToolPort loadMcpToolPort
    ) {
        return new SubscriptionConversationService(
                parseTaskUseCase,
                new ParsedTaskNormalizer(new DomainCapabilityRegistry()),
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
