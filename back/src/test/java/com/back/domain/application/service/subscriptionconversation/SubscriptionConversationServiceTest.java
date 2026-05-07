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
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.application.service.SubscriptionContext;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.subscription.SubscriptionConversationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Application: 구독 대화 서비스 테스트")
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
    private final FakeRunSubscriptionExecutionPort runSubscriptionExecutionPort =
            new FakeRunSubscriptionExecutionPort();

    @Test
    @DisplayName("새 메시지는 인증 사용자 id로 파싱하고 누락된 채널을 질문한다")
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
    @DisplayName("파서 확인 질문을 표시하고 parseSessionId로 대화를 이어간다")
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
    @DisplayName("후속 파서 결과는 이전 선택 채널과 내부 확인 주기를 유지한다")
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
    @DisplayName("퍼센트 조건 답변 후에도 채널보다 거래 유형 확인을 먼저 유지한다")
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
    @DisplayName("퍼센트 조건 답변은 모호한 아파트 거래 유형을 확정하지 않는다")
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
    @DisplayName("월세 초안의 짧은 조건 답변은 평균 월세 metric으로 저장한다")
    void shortConditionAnswerForMonthlyRentDraftUsesMonthlyRentMetric() {
        SubscriptionConversationJpaEntity savedConversation = new SubscriptionConversationJpaEntity(1L);
        savedConversation.updateParsedDraft(
                "parse-1",
                "강남구 오피스텔 월세",
                1L,
                "real-estate",
                "officetel_rent_price",
                "search_offi_rent",
                "{\"region\":\"강남구\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                null,
                NotificationChannel.TELEGRAM_DM,
                null,
                "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등",
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
                "5만원 이상 상승",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "AVG_MONTHLY_RENT")
                .containsEntry("conditionThreshold", "5")
                .containsEntry("conditionUnit", "MANWON");
    }

    @Test
    @DisplayName("MCP tool이 아직 없어도 퍼센트 조건 답변은 현재 모호한 대화를 유지한다")
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
    @DisplayName("needsConfirmation 파서 결과는 quick action 없이 파서 질문을 표시한다")
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
    @DisplayName("공고 수 조건이 있는 채용 요청은 확인 단계로 진행한다")
    void recruitmentRequestWithCountConditionAdvancesToConfirmation() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "create",
                "채용",
                "백엔드 채용 새 공고",
                "1건 이상 증가",
                "0 9 * * *",
                "telegram",
                "api",
                "백엔드 채용 공고",
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
                "백엔드 채용 새 공고 뜨면 텔레그램으로 알려줘",
                null
        );

        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().domainLabel()).isEqualTo("채용");
        assertThat(response.draft().intent()).isEqualTo("job_posting_change");
        assertThat(response.draft().monitoringParams())
                .containsEntry("dataToolName", "search_public_job")
                .containsEntry("keyword", "백엔드")
                .containsEntry("conditionMetric", "COUNT")
                .containsEntry("conditionUnit", "COUNT");
        assertThat(response.actions()).extracting(SubscriptionConversationService.ActionOption::type)
                .contains("CONFIRM_SUBSCRIPTION");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
    }

    @Test
    @DisplayName("키워드가 없는 채용 초안은 채널 선택 후 채용 대상을 질문한다")
    void recruitmentDraftWithoutKeywordAsksRecruitmentTargetAfterSelectingChannel() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "채용 알려줘",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
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

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("어떤 채용 공고");
        assertThat(response.assistantMessage()).doesNotContain("가격");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
    }

    @Test
    @DisplayName("조건이 없는 채용 초안은 채용 변화 조건을 질문한다")
    void recruitmentDraftWithoutConditionAsksRecruitmentChangeCondition() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "백엔드 채용",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"keyword\":\"백엔드\"," +
                        "\"recrut_pbanc_ttl\":\"백엔드\"" +
                        "}",
                "0 0 * * * *",
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

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("채용 공고 변화");
        assertThat(response.assistantMessage()).doesNotContain("가격");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
    }

    @Test
    @DisplayName("채용 조건 답변은 부동산 가격 파서로 완료하지 않는다")
    void recruitmentConditionAnswerIsNotCompletedByRealEstatePriceParser() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "백엔드 채용",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"keyword\":\"백엔드\"," +
                        "\"recrut_pbanc_ttl\":\"백엔드\"" +
                        "}",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "어떤 채용 공고 변화가 생기면 알림을 받을까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "create",
                "채용",
                "백엔드 채용 새 공고",
                "1건 이상 증가",
                "",
                "",
                "api",
                "백엔드 채용 공고",
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
                conversation.getId(),
                "5% 상승",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isEqualTo(1);
        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "COUNT")
                .containsEntry("conditionUnit", "COUNT");
    }

    @Test
    @DisplayName("채용 대상과 건수 조건 답변은 AI 이어가기 없이 초안을 보완한다")
    void recruitmentKeywordAndCountConditionAnswerCompletesLocally() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "채용 알림",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"" +
                        "}",
                "0 0 * * * *",
                null,
                null,
                "어떤 종류의 채용 공고를 모니터링하시겠어요? 그리고 어떤 조건일 때 알림을 받으시겠어요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                "백엔드 전체 5건 이상 변동이 생길때",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("채널");
        assertThat(conversation.getDraftQuery()).isEqualTo("백엔드 채용 공고");
        assertThat(conversation.getDraftMonitoringParams())
                .contains("\"keyword\":\"백엔드\"")
                .contains("\"recrut_pbanc_ttl\":\"백엔드\"")
                .contains("\"conditionMetric\":\"COUNT\"")
                .contains("\"conditionDirection\":\"ANY\"")
                .contains("\"conditionThreshold\":\"5\"")
                .contains("\"conditionUnit\":\"COUNT\"");
    }

    @Test
    @DisplayName("채용 조건 질문 뒤 키워드만 답하면 기본 조건을 확정하지 않고 조건을 다시 질문한다")
    void recruitmentKeywordOnlyAnswerAfterConditionQuestionAsksConditionAgain() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "채용 알림",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
                null,
                null,
                "어떤 종류의 채용 공고를 모니터링할까요? 또한, 어떤 조건으로 알림을 받을까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                "간호사",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("채용 공고 변화");
        assertThat(response.assistantMessage()).doesNotContain("채널");
        assertThat(conversation.getDraftMonitoringParams())
                .contains("\"keyword\":\"간호사\"")
                .contains("\"recrut_pbanc_ttl\":\"간호사\"")
                .doesNotContain("\"conditionMetric\"")
                .doesNotContain("\"conditionThreshold\"");
    }

    @Test
    @DisplayName("채용 조건만 답하면 조건만 보완하고 대상 키워드를 계속 질문한다")
    void recruitmentConditionOnlyAnswerDoesNotInventKeyword() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "채용 알림",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"" +
                        "}",
                "0 0 * * * *",
                null,
                null,
                "어떤 종류의 채용 공고를 모니터링하시겠어요? 그리고 어떤 조건일 때 알림을 받으시겠어요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                "새 공고가 1건 이상 등록되면",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("어떤 채용 공고");
        assertThat(conversation.getDraftMonitoringParams())
                .doesNotContain("\"keyword\"")
                .contains("\"conditionMetric\":\"COUNT\"")
                .contains("\"conditionDirection\":\"UP\"")
                .contains("\"conditionThreshold\":\"1\"");
    }

    @Test
    @DisplayName("부동산 조건 대기 중 채용 입력은 기존 가격 조건으로 흡수하지 않고 새 대화로 파싱한다")
    void realEstateConditionWaitStartsNewConversationForRecruitmentRequest() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-real-estate",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{" +
                        "\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"," +
                        "\"region\":\"강남구\"" +
                        "}",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "어떤 가격 변동 조건 시 알림을 받으시겠어요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-recruitment", List.of(new ParsedTask(
                "create",
                "채용",
                "백엔드 채용 새 공고",
                "1건 이상 증가",
                "",
                "telegram",
                "api",
                "백엔드 채용 공고",
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
                conversation.getId(),
                "백엔드 채용 새 공고 1건 이상 텔레그램으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().domainLabel()).isEqualTo("채용");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "COUNT")
                .containsEntry("conditionUnit", "COUNT")
                .doesNotContainEntry("conditionMetric", "AVG_PRICE");
    }

    @Test
    @DisplayName("부동산 채널 대기 중 채용 입력은 기존 draft 채널 선택으로 흡수하지 않는다")
    void realEstateChannelWaitStartsNewConversationForRecruitmentRequest() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-real-estate",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{" +
                        "\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"," +
                        "\"region\":\"강남구\"," +
                        "\"conditionMetric\":\"AVG_PRICE\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"5\"," +
                        "\"conditionUnit\":\"PERCENT\"" +
                        "}",
                "0 0 * * * *",
                null,
                null,
                "알림을 받을 채널을 선택해 주세요.",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-recruitment", List.of(new ParsedTask(
                "create",
                "채용",
                "백엔드 채용 새 공고",
                "1건 이상 증가",
                "",
                "telegram",
                "api",
                "백엔드 채용 공고",
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
                conversation.getId(),
                "백엔드 채용 새 공고 뜨면 텔레그램으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().domainLabel()).isEqualTo("채용");
        assertThat(response.draft().monitoringParams())
                .containsEntry("dataToolName", "search_public_job")
                .containsEntry("keyword", "백엔드")
                .containsEntry("conditionMetric", "COUNT");
    }

    @Test
    @DisplayName("부동산 채널 대기 중 영어 채용 입력도 기존 draft 채널 선택으로 흡수하지 않는다")
    void realEstateChannelWaitStartsNewConversationForEnglishRecruitmentRequest() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-real-estate",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{" +
                        "\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"," +
                        "\"region\":\"강남구\"," +
                        "\"conditionMetric\":\"AVG_PRICE\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"5\"," +
                        "\"conditionUnit\":\"PERCENT\"" +
                        "}",
                "0 0 * * * *",
                null,
                null,
                "알림을 받을 채널을 선택해 주세요.",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-recruitment", List.of(new ParsedTask(
                "create",
                "recruitment",
                "backend jobs",
                "1건 이상 증가",
                "",
                "telegram",
                "api",
                "backend jobs",
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
                conversation.getId(),
                "backend jobs telegram",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().domainLabel()).isEqualTo("채용");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "COUNT")
                .containsEntry("conditionUnit", "COUNT");
    }

    @Test
    @DisplayName("부동산 채널 대기 중 영어 단수 job 입력도 기존 draft 채널 선택으로 흡수하지 않는다")
    void realEstateChannelWaitStartsNewConversationForEnglishSingularJobRequest() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-real-estate",
                "강남구 아파트 매매 실거래가",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{" +
                        "\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"," +
                        "\"region\":\"강남구\"," +
                        "\"conditionMetric\":\"AVG_PRICE\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"5\"," +
                        "\"conditionUnit\":\"PERCENT\"" +
                        "}",
                "0 0 * * * *",
                null,
                null,
                "알림을 받을 채널을 선택해 주세요.",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-recruitment", List.of(new ParsedTask(
                "create",
                "recruitment",
                "backend job",
                "1건 이상 증가",
                "",
                "telegram",
                "api",
                "backend job",
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
                conversation.getId(),
                "backend job, telegram",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().domainLabel()).isEqualTo("채용");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "COUNT")
                .containsEntry("conditionUnit", "COUNT");
    }

    @Test
    @DisplayName("0건 채용 키워드 후보가 있으면 사용자 확인을 질문한다")
    void recruitmentDraftWithZeroResultKeywordSuggestionAsksForConfirmation() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "벡엔드 채용 공고",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"keyword\":\"벡엔드\"," +
                        "\"recrut_pbanc_ttl\":\"벡엔드\"," +
                        "\"keywordValidationStatus\":\"ZERO_RESULTS\"," +
                        "\"suggestedKeyword\":\"백엔드\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
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

        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.assistantMessage()).contains("벡엔드");
        assertThat(response.assistantMessage()).contains("백엔드");
        assertThat(response.assistantMessage()).doesNotContain("가격");
    }

    @Test
    @DisplayName("긍정 답변은 채용 키워드 후보를 적용한다")
    void affirmativeAnswerAcceptsRecruitmentKeywordSuggestion() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-1",
                "벡엔드 채용 공고",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"keyword\":\"벡엔드\"," +
                        "\"recrut_pbanc_ttl\":\"벡엔드\"," +
                        "\"keywordValidationStatus\":\"ZERO_RESULTS\"," +
                        "\"suggestedKeyword\":\"백엔드\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "현재 '벡엔드' 검색 결과가 없어요. '백엔드'를 뜻한 걸까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.continueResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "reject",
                "기타",
                "응",
                "",
                "",
                "",
                "",
                "응",
                List.of(),
                0.1,
                false,
                ""
        )));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                "응",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().monitoringParams())
                .containsEntry("keyword", "백엔드")
                .containsEntry("recrut_pbanc_ttl", "백엔드")
                .doesNotContainKey("keywordValidationStatus")
                .doesNotContainKey("suggestedKeyword");
    }

    @Test
    @DisplayName("채용 키워드 확인 중 부동산 입력은 키워드로 저장하지 않고 새 대화로 파싱한다")
    void recruitmentKeywordConfirmationStartsNewConversationForRealEstateRequest() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-recruitment",
                "벡엔드 채용 공고",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"keyword\":\"벡엔드\"," +
                        "\"recrut_pbanc_ttl\":\"벡엔드\"," +
                        "\"keywordValidationStatus\":\"ZERO_RESULTS\"," +
                        "\"keywordOriginal\":\"벡엔드\"," +
                        "\"suggestedKeyword\":\"백엔드\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "현재 '벡엔드' 검색 결과가 없어요. '백엔드'를 뜻한 걸까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-real-estate", List.of(new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "5% 이상 상승",
                "",
                "telegram",
                "api",
                "강남구 아파트 매매 실거래가 변동",
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
                conversation.getId(),
                "강남구 아파트 매매 실거래가 5% 상승 텔레그램으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().domainLabel()).isEqualTo("부동산");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "AVG_PRICE")
                .containsEntry("conditionUnit", "PERCENT");
    }

    @Test
    @DisplayName("채용 키워드 확인 중 채용 말고 부동산 입력은 부동산 새 대화로 파싱한다")
    void recruitmentKeywordConfirmationUsesLastExplicitDomainInMixedDomainRequest() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-recruitment",
                "벡엔드 채용 공고",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"keyword\":\"벡엔드\"," +
                        "\"recrut_pbanc_ttl\":\"벡엔드\"," +
                        "\"keywordValidationStatus\":\"ZERO_RESULTS\"," +
                        "\"keywordOriginal\":\"벡엔드\"," +
                        "\"suggestedKeyword\":\"백엔드\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "현재 '벡엔드' 검색 결과가 없어요. '백엔드'를 뜻한 걸까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-real-estate", List.of(new ParsedTask(
                "create",
                "부동산",
                "강남구 아파트 매매 실거래가",
                "5% 이상 상승",
                "",
                "telegram",
                "api",
                "강남구 아파트 매매 실거래가 변동",
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
                conversation.getId(),
                "채용 말고 강남구 아파트 매매 실거래가 5% 상승 텔레그램으로 알려줘",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isEqualTo(1);
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().domainLabel()).isEqualTo("부동산");
        assertThat(response.draft().monitoringParams())
                .containsEntry("conditionMetric", "AVG_PRICE")
                .containsEntry("conditionUnit", "PERCENT");
    }

    @Test
    @DisplayName("채용 키워드 확인 중 아파트 직무 입력은 부동산 새 대화가 아니라 키워드로 저장한다")
    void recruitmentKeywordConfirmationKeepsApartmentJobKeywordInRecruitmentDraft() {
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(1L);
        conversation.updateParsedDraft(
                "parse-recruitment",
                "벡엔드 채용 공고",
                3L,
                "recruitment",
                "job_posting_change",
                "search_public_job",
                "{" +
                        "\"dataToolName\":\"search_public_job\"," +
                        "\"page_no\":\"1\"," +
                        "\"num_of_rows\":\"20\"," +
                        "\"ongoing_yn\":\"Y\"," +
                        "\"keyword\":\"벡엔드\"," +
                        "\"recrut_pbanc_ttl\":\"벡엔드\"," +
                        "\"keywordValidationStatus\":\"ZERO_RESULTS\"," +
                        "\"keywordOriginal\":\"벡엔드\"," +
                        "\"suggestedKeyword\":\"백엔드\"," +
                        "\"conditionMetric\":\"COUNT\"," +
                        "\"conditionDirection\":\"UP\"," +
                        "\"conditionOperator\":\"GTE\"," +
                        "\"conditionThreshold\":\"1\"," +
                        "\"conditionUnit\":\"COUNT\"" +
                        "}",
                "0 0 * * * *",
                NotificationChannel.TELEGRAM_DM,
                null,
                "현재 '벡엔드' 검색 결과가 없어요. '백엔드'를 뜻한 걸까요?",
                SubscriptionConversationStatus.COLLECTING
        );
        when(conversationRepository.findByIdAndUserId(conversation.getId(), 1L))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-real-estate", List.of(realEstateTask(false)));
        LoadNotificationEndpointPort connectedTelegram = (userId, channel) -> channel == NotificationChannel.TELEGRAM_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "123456789", true))
                : Optional.empty();
        SubscriptionConversationService service = service(connectedTelegram);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                conversation.getId(),
                "아파트 경비",
                null
        );

        assertThat(parseTaskUseCase.parseCallCount).isZero();
        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().domainLabel()).isEqualTo("채용");
        assertThat(response.draft().monitoringParams())
                .containsEntry("keyword", "아파트 경비")
                .containsEntry("recrut_pbanc_ttl", "아파트 경비")
                .doesNotContainKeys("keywordValidationStatus", "keywordOriginal", "suggestedKeyword");
    }

    @Test
    @DisplayName("기획 상태 도메인은 구독을 생성하지 않는다")
    void plannedDomainDoesNotCreateSubscription() {
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        parseTaskUseCase.parseResult = new ParseResult("parse-1", List.of(new ParsedTask(
                "create",
                "법률",
                "개인정보보호법 개정",
                "1건 이상 증가",
                "0 * * * *",
                "email",
                "api",
                "개인정보보호법 개정",
                List.of(),
                0.9,
                false,
                ""
        )));
        SubscriptionConversationService service = service(loadNotificationEndpointPort);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                null,
                "개인정보보호법 개정되면 이메일로 알려줘",
                null
        );

        assertThat(response.assistantMessage()).contains("준비 중");
        assertThat(createSubscriptionUseCase.receivedCommand).isNull();
    }

    // draftUsesStoredMcpTool 테스트 제거:
    // withStoredMcpTool()이 Spring AI 위임으로 제거됨 → toolName은 더 이상 저장되지 않음

    @Test
    @DisplayName("연결되지 않은 DM 채널을 명시하면 확인 전에 연결을 요구한다")
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
    @DisplayName("모호한 아파트 변경 요청은 확인 단계 대신 거래 유형을 질문한다")
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
    @DisplayName("일반 아파트 가격 요청은 조건보다 거래 유형을 먼저 질문한다")
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
    @DisplayName("거래 유형 확인 대기는 AI 이어가기 없이 짧은 매매 답변을 수락한다")
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
    @DisplayName("거래 유형 확인 대기는 AI 이어가기 없이 짧은 전월세 답변을 수락한다")
    void pendingDealTypeConfirmationAcceptsShortRentAnswer() {
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
                "부동산 가격은 매매/전월세 중 어떤 기준인가요?",
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
                "전월세로 해줘",
                null
        );

        assertThat(parseTaskUseCase.continueCallCount).isZero();
        assertThat(response.status()).isEqualTo("READY_FOR_CONFIRMATION");
        assertThat(response.draft().query()).isEqualTo("강남구 아파트 전월세 실거래가");
        assertThat(response.draft().intent()).isEqualTo("apartment_rent_price");
        assertThat(response.draft().toolName()).isEqualTo("search_apt_rent");
        assertThat(response.draft().monitoringParams()).doesNotContainKey("pendingDealTypeConfirmation");
    }

    @Test
    @DisplayName("지원하지 않는 초안 뒤의 자유 입력은 새 파싱을 시작한다")
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
    @DisplayName("확정된 준비 초안은 구독과 모니터링 설정 저장 후 baseline을 초기화한다")
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
        assertThat(runSubscriptionExecutionPort.contexts).hasSize(1);
        SubscriptionContext baselineContext = runSubscriptionExecutionPort.contexts.getFirst();
        assertThat(baselineContext.subscriptionId()).isEqualTo("sub-1");
        assertThat(baselineContext.domain()).isEqualTo("real-estate");
        assertThat(baselineContext.params())
                .containsEntry("region", "강남구")
                .containsEntry("deal_ymd", latestAvailableDealYmd())
                .containsEntry("dataToolName", "search_house_price");
        assertThat(baselineContext.notificationChannel()).isEqualTo("TELEGRAM_DM");
        assertThat(baselineContext.notificationTarget()).isEqualTo("123456789");
    }

    @Test
    @DisplayName("baseline 초기화가 실패해도 구독 확정 응답은 성공으로 처리한다")
    void confirmStillCreatesSubscriptionWhenBaselineInitializationFails() {
        SubscriptionConversationJpaEntity readyConversation = new SubscriptionConversationJpaEntity(1L);
        readyConversation.updateParsedDraft(
                "parse-1",
                "안산 상록구 아파트 매매",
                1L,
                "real-estate",
                "apartment_trade_price",
                "search_house_price",
                "{\"region\":\"안산 상록구\",\"conditionMetric\":\"AVG_PRICE\",\"conditionDirection\":\"UP\",\"conditionOperator\":\"GTE\",\"conditionThreshold\":\"5\",\"conditionUnit\":\"PERCENT\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}",
                "0 0 * * * *",
                NotificationChannel.DISCORD_DM,
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
                "안산 상록구 아파트 매매",
                true,
                LocalDateTime.now(),
                "schedule-1",
                "0 0 * * * *",
                LocalDateTime.now().plusHours(1)
        );
        LoadNotificationEndpointPort connectedDiscord = (userId, channel) -> channel == NotificationChannel.DISCORD_DM
                ? Optional.of(new NotificationEndpoint("endpoint-1", userId, channel, "discord-user-1", true))
                : Optional.empty();
        runSubscriptionExecutionPort.failure = new RuntimeException("baseline failed");
        SubscriptionConversationService service = service(connectedDiscord);

        SubscriptionConversationService.Response response = service.handle(
                1L,
                readyConversation.getId(),
                null,
                new SubscriptionConversationService.ActionRequest("CONFIRM_SUBSCRIPTION", "confirm")
        );

        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(response.subscription().id()).isEqualTo("sub-1");
        assertThat(readyConversation.getStatus()).isEqualTo(SubscriptionConversationStatus.CREATED);
        verify(monitoringConfigRepository).save(any());
    }

    @Test
    @DisplayName("조건이 없는 준비 초안은 구독 생성 대신 조건을 질문한다")
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
    @DisplayName("연결된 채널을 선택하면 초안이 확인 단계로 진행한다")
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
    @DisplayName("채널 직접 입력은 새 요청으로 재파싱하지 않고 로컬에서 처리한다")
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
                new ObjectMapper(),
                runSubscriptionExecutionPort
        );
    }

    private static String latestAvailableDealYmd() {
        return LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
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
            if ("recruitment".equals(domainName)) {
                return Optional.of(recruitmentDraft(query, task, userMessage));
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

        private static DomainNormalizedSubscriptionDraft recruitmentDraft(
                String query,
                ParsedTask task,
                String userMessage
        ) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("dataToolName", "search_public_job");
            params.put("page_no", "1");
            params.put("num_of_rows", "20");
            params.put("ongoing_yn", "Y");

            String keyword = recruitmentKeyword(query, task.target());
            if (!isBlank(keyword)) {
                params.put("keyword", keyword);
                params.put("recrut_pbanc_ttl", keyword);
            }

            params.putAll(recruitmentConditionParams(task.condition(), userMessage, task.target()));

            List<String> missing = new java.util.ArrayList<>();
            if (!params.containsKey("keyword")) {
                missing.add("keyword");
            }
            if (StructuredCondition.fromParameters(params).isEmpty()) {
                missing.add("condition");
            }

            return new DomainNormalizedSubscriptionDraft(
                    query,
                    "recruitment",
                    "job_posting_change",
                    "search_public_job",
                    params,
                    missing,
                    recruitmentQuestionForMissing(missing),
                    task.confidence()
            );
        }

        private static String recruitmentKeyword(String query, String target) {
            String text = !isBlank(query) ? query : target;
            if (isBlank(text)) {
                return "";
            }
            String keyword = text
                    .replace("진행중 공고", "")
                    .replace("새 공고", "")
                    .replace("신규 공고", "")
                    .replace("채용", "")
                    .replace("공고", "")
                    .trim();
            if (keyword.startsWith("공공기관 ")) {
                keyword = keyword.substring("공공기관 ".length()).trim();
            }
            return keyword;
        }

        private static Map<String, String> recruitmentConditionParams(
                String condition,
                String userMessage,
                String target
        ) {
            String text = (condition == null ? "" : condition) + " "
                    + (userMessage == null ? "" : userMessage) + " "
                    + (target == null ? "" : target);
            if (!containsAny(text, "새 공고", "신규", "등록", "증가", "늘면", "이상")) {
                return Map.of();
            }
            String threshold = condition == null ? "" : condition.replaceAll("[^0-9.]", "");
            if (threshold.isBlank()) {
                threshold = "1";
            }
            return Map.of(
                    "conditionMetric", text.contains("진행중") ? "ONGOING_COUNT" : "COUNT",
                    "conditionDirection", "UP",
                    "conditionOperator", "GTE",
                    "conditionThreshold", threshold,
                    "conditionUnit", "COUNT"
            );
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
                return "부동산 가격은 매매/전월세 중 어떤 기준인가요?";
            }
            if (missing.contains("condition")) {
                return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등";
            }
            return "";
        }

        private static String recruitmentQuestionForMissing(List<String> missing) {
            if (missing.contains("keyword")) {
                return "어떤 채용 공고를 구독할까요? 예: 백엔드, 데이터, 공공기관 인턴 등";
            }
            if (missing.contains("condition")) {
                return "어떤 채용 공고 변화가 생기면 알림을 받을까요? 예: 새 공고 1건 이상 등록 등";
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

    private static class FakeRunSubscriptionExecutionPort implements RunSubscriptionExecutionPort {
        private final List<SubscriptionContext> contexts = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public void execute(List<SubscriptionContext> subscriptions) {
            contexts.clear();
            contexts.addAll(subscriptions);
            if (failure != null) {
                throw failure;
            }
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
