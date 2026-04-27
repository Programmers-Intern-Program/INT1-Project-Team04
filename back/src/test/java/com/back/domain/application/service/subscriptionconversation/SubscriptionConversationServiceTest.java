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
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.model.domain.Domain;
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
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

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
                "{\"region\":\"강남구\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 9 * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "몇 % 이상 변동 시 알려드릴까요?",
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
                "상승 기준으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.receivedContinueSessionId).isEqualTo("parse-1");
        assertThat(response.conversationId()).isEqualTo(savedConversation.getId());
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
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

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
                "{\"region\":\"강남구\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
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

    private SubscriptionConversationService service(LoadNotificationEndpointPort endpointPort) {
        return new SubscriptionConversationService(
                parseTaskUseCase,
                new ParsedTaskNormalizer(new DomainCapabilityRegistry()),
                createSubscriptionUseCase,
                loadDomainPort,
                endpointPort,
                conversationRepository,
                monitoringConfigRepository,
                new ObjectMapper()
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
