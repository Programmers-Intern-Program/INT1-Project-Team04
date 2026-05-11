package com.back.domain.application.service.subscriptionconversation;

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
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.result.SubscriptionResult;
import com.back.domain.application.service.SubscriptionContext;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.subscription.SubscriptionConversationStatus;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionConversationService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String PENDING_DEAL_TYPE_CONFIRMATION = "pendingDealTypeConfirmation";
    private static final String KEYWORD_CONFIRMATION = "keywordConfirmation";
    private static final String KEYWORD_VALIDATION_STATUS = "keywordValidationStatus";
    private static final String ZERO_RESULTS = "ZERO_RESULTS";
    private static final String SUGGESTED_KEYWORD = "suggestedKeyword";
    private static final String KEYWORD_ORIGINAL = "keywordOriginal";
    private static final String DEFAULT_INTERNAL_CHECK_CRON = "0 0 * * * *";
    private static final Pattern RECRUITMENT_COUNT_THRESHOLD = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:건|개)");
    private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final ParseTaskUseCase parseTaskUseCase;
    private final ParsedTaskNormalizer parsedTaskNormalizer;
    private final CreateSubscriptionUseCase createSubscriptionUseCase;
    private final LoadDomainPort loadDomainPort;
    private final LoadMcpToolPort loadMcpToolPort;
    private final LoadNotificationEndpointPort loadNotificationEndpointPort;
    private final SubscriptionConversationJpaRepository conversationRepository;
    private final SubscriptionMonitoringConfigJpaRepository monitoringConfigRepository;
    private final ObjectMapper objectMapper;
    private final RunSubscriptionExecutionPort runSubscriptionExecutionPort;

    public Response handle(Long userId, String conversationId, String message, ActionRequest action) {
        if (action != null) {
            return handleAction(userId, conversationId, action);
        }
        if (conversationId == null || conversationId.isBlank()) {
            return handleNewMessage(userId, message);
        }
        return handleContinuedMessage(userId, conversationId, message);
    }

    private Response handleNewMessage(Long userId, String message) {
        ParseResult parseResult = parseTaskUseCase.parse(new ParseTaskCommand(userId, message));
        SubscriptionConversationJpaEntity conversation = new SubscriptionConversationJpaEntity(userId);
        return applyParseResult(conversation, parseResult, message);
    }

    private Response handleContinuedMessage(Long userId, String conversationId, String message) {
        SubscriptionConversationJpaEntity conversation = loadConversation(userId, conversationId);
        if (shouldStartNewConversation(conversation, message)) {
            return handleNewMessage(userId, message);
        }

        if (conversation.getDraftNotificationChannel() == NotificationChannel.EMAIL
                && isBlank(conversation.getDraftNotificationTargetAddress())
                && isEmail(message)) {
            conversation.updateChannel(NotificationChannel.EMAIL, message.trim());
            return completeOrAsk(conversation);
        }

        Response conditionResponse = completeConditionIfPossible(conversation, message);
        if (conditionResponse != null) {
            return conditionResponse;
        }

        Response shortAnswerResponse = completeShortAnswerIfPossible(conversation, message);
        if (shortAnswerResponse != null) {
            return shortAnswerResponse;
        }

        ParseResult parseResult = parseTaskUseCase.continueParse(
                new ContinueParseCommand(userId, conversation.getParseSessionId(), message)
        );
        return applyParseResult(conversation, parseResult, message);
    }

    private boolean shouldStartNewConversation(SubscriptionConversationJpaEntity conversation, String message) {
        if (isBlank(message)) {
            return false;
        }
        if (conversation.getStatus() == SubscriptionConversationStatus.CREATED
                || conversation.getStatus() == SubscriptionConversationStatus.CANCELLED) {
            return true;
        }
        Optional<String> requestedDomain = explicitDomainFromMessage(message);
        if (requestedDomain.isPresent()
                && !isBlank(conversation.getDraftDomainName())
                && !requestedDomain.get().equals(conversation.getDraftDomainName())
                && allowsDomainSwitch(conversation, requestedDomain.get(), message)) {
            return true;
        }
        return isUnsupportedConversation(conversation)
                || (isBlank(conversation.getDraftDomainName()) && isBlank(conversation.getDraftQuery()));
    }

    private boolean isUnsupportedConversation(SubscriptionConversationJpaEntity conversation) {
        return "reject".equals(conversation.getDraftIntent())
                || "unsupportedDomain".equals(conversation.getDraftIntent())
                || (isBlank(conversation.getDraftIntent()) && isBlank(conversation.getDraftToolName()))
                || (conversation.getDraftDomainId() == null && isBlank(conversation.getDraftToolName()));
    }

    private Response applyParseResult(
            SubscriptionConversationJpaEntity conversation,
            ParseResult parseResult,
            String userMessage
    ) {
        if (parseResult.tasks().size() != 1) {
            conversation.updateStatus(
                    SubscriptionConversationStatus.COLLECTING,
                    "한 번에 하나의 알림만 만들 수 있어요. 만들 알림 하나만 다시 입력해 주세요."
            );
            conversationRepository.save(conversation);
            return needsInput(conversation, conversation.getLastAssistantMessage(), List.of());
        }

        ParsedTask task = parseResult.tasks().getFirst();
        SubscriptionDraft draft = parsedTaskNormalizer.normalize(task, userMessage, previousDraft(conversation));
        Long domainId = findDomainId(draft.domainName()).orElse(null);
        // toolName은 SubscriptionMonitorService(Spring AI)가 MCP server에 위임하므로 더 이상 여기서 세팅 불필요
        // draft = withStoredMcpTool(draft, domainId);
        NotificationChannel channel = parseChannel(draft.notificationChannel()).orElse(null);
        String assistantMessage = assistantMessage(task, draft);
        boolean waitsForParserConfirmation = task.needsConfirmation() && !isUnsupported(draft);
        SubscriptionConversationStatus status = !waitsForParserConfirmation && isDraftComplete(draft, domainId, channel)
                ? SubscriptionConversationStatus.READY_FOR_CONFIRMATION
                : SubscriptionConversationStatus.COLLECTING;

        conversation.updateParsedDraft(
                parseResult.sessionId(),
                draft.query(),
                domainId,
                draft.domainName(),
                draft.intent(),
                draft.toolName(),
                toJson(draft.monitoringParams()),
                draft.cronExpr(),
                channel,
                draft.notificationTargetAddress(),
                status == SubscriptionConversationStatus.READY_FOR_CONFIRMATION
                        ? confirmationMessage()
                        : assistantMessage,
                status
        );
        conversationRepository.save(conversation);

        if (isUnsupported(draft) || waitsForParserConfirmation) {
            List<ActionOption> actions = waitsForParserConfirmation
                    ? List.of()
                    : actionsForMissing(draft.missingFields(), conversation.getUserId());
            return needsInput(conversation, assistantMessage, actions);
        }
        return completeOrAsk(conversation);
    }

    private Response handleAction(Long userId, String conversationId, ActionRequest action) {
        SubscriptionConversationJpaEntity conversation = loadConversation(userId, conversationId);
        return switch (action.type()) {
            case "SELECT_CHANNEL" -> selectChannel(conversation, action.value());
            case "CONFIRM_SUBSCRIPTION" -> confirm(userId, conversation);
            case "CANCEL_CONVERSATION" -> cancel(conversation);
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST);
        };
    }

    private Response selectChannel(SubscriptionConversationJpaEntity conversation, String value) {
        NotificationChannel channel = parseChannel(value).orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
        Optional<NotificationEndpoint> endpoint = loadNotificationEndpointPort
                .loadEnabledByUserIdAndChannel(conversation.getUserId(), channel);

        if (endpoint.isPresent()) {
            conversation.updateChannel(channel, null);
            return completeOrAsk(conversation);
        }

        if (channel == NotificationChannel.EMAIL) {
            conversation.updateChannel(channel, null);
            conversation.updateStatus(SubscriptionConversationStatus.COLLECTING, "알림을 받을 이메일 주소를 입력해 주세요.");
            conversationRepository.save(conversation);
            return needsInput(conversation, conversation.getLastAssistantMessage(), List.of());
        }

        String label = channelLabel(channel);
        conversation.updateStatus(SubscriptionConversationStatus.COLLECTING, label + " 연결이 필요합니다.");
        conversationRepository.save(conversation);
        return needsInput(conversation, conversation.getLastAssistantMessage(), channelActions(conversation.getUserId()));
    }

    private Response completeOrAsk(SubscriptionConversationJpaEntity conversation) {
        // resolveMissingMcpTool(conversation); // Spring AI 위임으로 불필요
        ensureInternalCheckCron(conversation);
        List<String> missing = missingPersistedFields(conversation);
        if (missing.isEmpty()) {
            conversation.updateStatus(SubscriptionConversationStatus.READY_FOR_CONFIRMATION, confirmationMessage());
            conversationRepository.save(conversation);
            return readyForConfirmation(conversation);
        }

        String message = questionForMissing(missing, conversation);
        conversation.updateStatus(SubscriptionConversationStatus.COLLECTING, message);
        conversationRepository.save(conversation);
        return needsInput(conversation, message, actionsForMissing(missing, conversation.getUserId()));
    }

    // @Deprecated: MCP tool 선택이 Spring AI(MCP server)로 위임되면서 호출 제거됨. 참조용으로 보존.
    @SuppressWarnings("unused")
    private void resolveMissingMcpTool(SubscriptionConversationJpaEntity conversation) {
        if (!isBlank(conversation.getDraftToolName()) || conversation.getDraftDomainId() == null) {
            return;
        }

        loadMcpToolPort.loadByDomainId(conversation.getDraftDomainId())
                .ifPresent(tool -> conversation.updateParsedDraft(
                        conversation.getParseSessionId(),
                        conversation.getDraftQuery(),
                        conversation.getDraftDomainId(),
                        conversation.getDraftDomainName(),
                        conversation.getDraftIntent(),
                        tool.name(),
                        conversation.getDraftMonitoringParams(),
                        conversation.getDraftCronExpr(),
                        conversation.getDraftNotificationChannel(),
                        conversation.getDraftNotificationTargetAddress(),
                        conversation.getLastAssistantMessage(),
                        conversation.getStatus()
                ));
    }

    private Response confirm(Long userId, SubscriptionConversationJpaEntity conversation) {
        if (conversation.getStatus() != SubscriptionConversationStatus.READY_FOR_CONFIRMATION) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        // resolveMissingMcpTool(conversation); // Spring AI 위임으로 불필요
        ensureInternalCheckCron(conversation);
        List<String> missing = missingPersistedFields(conversation);
        if (!missing.isEmpty()) {
            String message = questionForMissing(missing, conversation);
            conversation.updateStatus(SubscriptionConversationStatus.COLLECTING, message);
            conversationRepository.save(conversation);
            return needsInput(conversation, message, actionsForMissing(missing, conversation.getUserId()));
        }

        SubscriptionResult result = createSubscriptionUseCase.createForUser(userId, new CreateSubscriptionCommand(
                conversation.getDraftDomainId(),
                conversation.getDraftQuery(),
                conversation.getDraftCronExpr(),
                conversation.getDraftNotificationChannel(),
                conversation.getDraftNotificationTargetAddress()
        ));
        // toolName은 null로 저장됨 (withStoredMcpTool 제거로 세팅 안 됨).
        // 실행 시 Spring AI가 domain + parametersJson(region, condition 등)을 보고 MCP tool을 직접 선택하므로 문제 없음.
        // parametersJson이 핵심 데이터 — SubscriptionMonitorService.buildContext()에서 SubscriptionContext.params로 전달됨.
        monitoringConfigRepository.save(new SubscriptionMonitoringConfigJpaEntity(
                result.id(),
                conversation.getDraftToolName(),
                conversation.getDraftIntent(),
                conversation.getDraftMonitoringParams()
        ));
        initializeBaseline(userId, conversation, result);
        conversation.updateStatus(SubscriptionConversationStatus.CREATED, "알림을 시작했어요.");
        conversationRepository.save(conversation);

        return new Response(
                conversation.getId(),
                "CREATED",
                conversation.getLastAssistantMessage(),
                null,
                List.of(),
                new CreatedSubscription(result.id(), result.nextRun())
        );
    }

    private void initializeBaseline(
            Long userId,
            SubscriptionConversationJpaEntity conversation,
            SubscriptionResult result
    ) {
        // 구독 확정 응답은 첫 baseline 수집까지 성공해야 실제로 감시가 시작됐다고 본다.
        runSubscriptionExecutionPort.execute(List.of(new SubscriptionContext(
                result.id(),
                conversation.getDraftDomainName(),
                result.query(),
                baselineParams(conversation),
                conversation.getDraftNotificationChannel() != null
                        ? conversation.getDraftNotificationChannel().name()
                        : null,
                notificationTarget(userId, conversation)
        )));
    }

    private Map<String, Object> baselineParams(SubscriptionConversationJpaEntity conversation) {
        Map<String, Object> params = new LinkedHashMap<>(monitoringParams(conversation.getDraftMonitoringParams()));
        putConfiguredToolName(params, conversation.getDraftToolName());
        if ("LATEST_AVAILABLE_MONTH".equals(String.valueOf(params.get("dealYmdPolicy")))
                && !params.containsKey("deal_ymd")
                && !params.containsKey("dealYmd")) {
            params.put("deal_ymd", LocalDateTime.now().minusMonths(1).format(DEAL_YMD_FORMATTER));
        }
        return params;
    }

    private void putConfiguredToolName(Map<String, Object> params, String toolName) {
        if (!isBlank(toolName)) {
            params.put("dataToolName", toolName);
        }
    }

    private String notificationTarget(Long userId, SubscriptionConversationJpaEntity conversation) {
        NotificationChannel channel = conversation.getDraftNotificationChannel();
        if (channel == null) {
            return null;
        }
        if (!isBlank(conversation.getDraftNotificationTargetAddress())) {
            return conversation.getDraftNotificationTargetAddress().trim();
        }
        return loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(userId, channel)
                .map(NotificationEndpoint::targetAddress)
                .orElse(null);
    }

    private Response cancel(SubscriptionConversationJpaEntity conversation) {
        conversation.updateStatus(SubscriptionConversationStatus.CANCELLED, "알림 생성을 취소했어요.");
        conversationRepository.save(conversation);
        return new Response(conversation.getId(), "CANCELLED", conversation.getLastAssistantMessage(), null, List.of(), null);
    }

    private SubscriptionConversationJpaEntity loadConversation(Long userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
    }

    private Optional<Long> findDomainId(String domainName) {
        return loadDomainPort.loadAll().stream()
                .filter(domain -> domain.name().equals(domainName))
                .map(Domain::id)
                .findFirst();
    }

    private SubscriptionDraft previousDraft(SubscriptionConversationJpaEntity conversation) {
        if (isBlank(conversation.getDraftQuery()) && isBlank(conversation.getDraftDomainName())) {
            return null;
        }
        return new SubscriptionDraft(
                conversation.getDraftQuery(),
                conversation.getDraftDomainName(),
                conversation.getDraftIntent(),
                conversation.getDraftToolName(),
                monitoringParams(conversation.getDraftMonitoringParams()),
                conversation.getDraftCronExpr(),
                conversation.getDraftNotificationChannel() == null
                        ? null
                        : conversation.getDraftNotificationChannel().name(),
                conversation.getDraftNotificationTargetAddress(),
                List.of(),
                conversation.getLastAssistantMessage(),
                0
        );
    }

    // @Deprecated: MCP tool 선택이 Spring AI(MCP server)로 위임되면서 호출 제거됨. 참조용으로 보존.
    @SuppressWarnings("unused")
    private SubscriptionDraft withStoredMcpTool(SubscriptionDraft draft, Long domainId) {
        if (domainId == null || isUnsupported(draft)) {
            return draft;
        }

        Optional<McpTool> configuredTool = Optional.ofNullable(draft.toolName())
                .filter(toolName -> !isBlank(toolName))
                .flatMap(toolName -> loadMcpToolPort.loadByDomainIdAndName(domainId, toolName));
        return configuredTool
                .or(() -> loadMcpToolPort.loadByDomainId(domainId))
                .map(tool -> withToolName(draft, tool.name()))
                .orElse(draft);
    }

    private SubscriptionDraft withToolName(SubscriptionDraft draft, String toolName) {
        return new SubscriptionDraft(
                draft.query(),
                draft.domainName(),
                draft.intent(),
                toolName,
                draft.monitoringParams(),
                draft.cronExpr(),
                draft.notificationChannel(),
                draft.notificationTargetAddress(),
                draft.missingFields(),
                draft.assistantMessage(),
                draft.confidence()
        );
    }

    private boolean isDraftComplete(SubscriptionDraft draft, Long domainId, NotificationChannel channel) {
        return domainId != null
                && !isBlank(draft.query())
                && !isBlank(draft.intent())
                // toolName 조건 제거 — MCP tool 선택은 Spring AI(MCP server)가 담당
                && StructuredCondition.fromParameters(draft.monitoringParams()).isPresent()
                && !isBlank(draft.cronExpr())
                && channel != null
                && draft.missingFields().isEmpty();
    }

    private String assistantMessage(ParsedTask task, SubscriptionDraft draft) {
        if (isUnsupported(draft)) {
            return draft.assistantMessage();
        }
        if (task.needsConfirmation() && !isBlank(task.confirmationQuestion())) {
            return task.confirmationQuestion();
        }
        return draft.assistantMessage();
    }

    private boolean isUnsupported(SubscriptionDraft draft) {
        return draft.missingFields().contains("unsupportedDomain")
                || draft.missingFields().contains("unsupportedIntent")
                || draft.missingFields().contains("unsupportedCapability");
    }

    private List<ActionOption> actionsForMissing(List<String> missing, Long userId) {
        if (missing.contains("condition")) {
            return List.of();
        }
        if (missing.contains("dealType")) {
            return List.of();
        }
        if (missing.contains("notificationChannel")) {
            return channelActions(userId);
        }
        if (missing.contains("emailAddress")) {
            return List.of();
        }
        if (missing.contains("notificationEndpoint")) {
            return channelActions(userId);
        }
        return List.of();
    }

    private Response completeConditionIfPossible(
            SubscriptionConversationJpaEntity conversation,
            String message
    ) {
        if (conversation.getStatus() != SubscriptionConversationStatus.COLLECTING
                || !missingPersistedFields(conversation).contains("condition")) {
            return null;
        }
        // 채용 조건은 COUNT 계열이므로 부동산 가격 조건 파서로 보완하지 않는다.
        if (!isRealEstateDraft(conversation)) {
            return null;
        }

        Optional<StructuredCondition> condition = StructuredCondition.parse(message, conditionMetricContext(conversation));
        if (condition.isEmpty()) {
            return null;
        }

        Map<String, String> monitoringParams = new HashMap<>(monitoringParams(conversation.getDraftMonitoringParams()));
        monitoringParams.remove("condition");
        monitoringParams.putAll(condition.get().toParameterMap());
        conversation.updateParsedDraft(
                conversation.getParseSessionId(),
                conversation.getDraftQuery(),
                conversation.getDraftDomainId(),
                conversation.getDraftDomainName(),
                conversation.getDraftIntent(),
                conversation.getDraftToolName(),
                toJson(monitoringParams),
                conversation.getDraftCronExpr(),
                conversation.getDraftNotificationChannel(),
                conversation.getDraftNotificationTargetAddress(),
                conversation.getLastAssistantMessage(),
                conversation.getStatus()
        );
        return completeOrAsk(conversation);
    }

    private String conditionMetricContext(SubscriptionConversationJpaEntity conversation) {
        return (emptyIfBlank(conversation.getDraftQuery()) + " "
                + emptyIfBlank(conversation.getDraftIntent()) + " "
                + emptyIfBlank(conversation.getDraftToolName())).strip();
    }

    private Response completeShortAnswerIfPossible(
            SubscriptionConversationJpaEntity conversation,
            String message
    ) {
        List<String> missing = missingPersistedFields(conversation);
        if (missing.contains(KEYWORD_CONFIRMATION)) {
            Response response = completeKeywordConfirmationIfPossible(conversation, message);
            if (response != null) {
                return response;
            }
        }

        // 채용 대상/건수 조건처럼 짧은 후속 답변은 AI JSON 파싱 실패가 잦아 로컬에서 먼저 보완한다.
        Response recruitmentResponse = completeRecruitmentAnswerIfPossible(conversation, message, missing);
        if (recruitmentResponse != null) {
            return recruitmentResponse;
        }

        if (missing.contains("dealType")) {
            Optional<String> dealType = parseDealTypeAnswer(message);
            if (dealType.isPresent()) {
                return selectDealType(conversation, dealType.get());
            }
        }

        if (missing.contains("notificationChannel")) {
            Optional<NotificationChannel> channel = parseChannelAnswer(message);
            if (channel.isPresent()) {
                return selectChannel(conversation, channel.get().name());
            }
        }

        return null;
    }

    private Response completeRecruitmentAnswerIfPossible(
            SubscriptionConversationJpaEntity conversation,
            String message,
            List<String> missing
    ) {
        boolean askedRecruitmentCondition = askedRecruitmentCondition(conversation);
        if (!isRecruitmentDraft(conversation)
                || (!missing.contains("keyword") && !missing.contains("condition") && !askedRecruitmentCondition)) {
            return null;
        }

        Map<String, String> params = new HashMap<>(monitoringParams(conversation.getDraftMonitoringParams()));
        boolean updated = false;

        Optional<String> keyword = Optional.empty();
        if (missing.contains("keyword")) {
            keyword = parseRecruitmentKeywordAnswer(message);
            if (keyword.isPresent()) {
                clearRecruitmentKeywordValidation(params);
                params.put("keyword", keyword.get());
                if ("search_public_job".equals(params.getOrDefault("dataToolName", "search_public_job"))) {
                    params.put("recrut_pbanc_ttl", keyword.get());
                }
                updated = true;
            }
        }

        Optional<Map<String, String>> condition = Optional.empty();
        if (missing.contains("condition") || askedRecruitmentCondition) {
            // 채용 조건은 가격 조건과 달리 공고 수 delta 기준 COUNT 파라미터로 저장한다.
            condition = parseRecruitmentConditionAnswer(message);
            if (condition.isPresent()) {
                params.putAll(condition.get());
                updated = true;
            }
        }

        if (keyword.isPresent() && askedRecruitmentCondition && condition.isEmpty()) {
            // 조건을 함께 물은 턴에서는 대상만 답한 값을 기존 기본 조건 확정으로 취급하지 않는다.
            clearRecruitmentCondition(params);
        }

        if (!updated) {
            return null;
        }

        ensureRecruitmentDefaults(params);
        String queryKeyword = keyword.orElse(params.get("keyword"));
        updateRecruitmentParams(
                conversation,
                params,
                isBlank(queryKeyword) ? conversation.getDraftQuery() : queryKeyword + " 채용 공고"
        );
        return completeOrAsk(conversation);
    }

    private boolean askedRecruitmentCondition(SubscriptionConversationJpaEntity conversation) {
        if (!isRecruitmentDraft(conversation)) {
            return false;
        }
        String message = conversation.getLastAssistantMessage();
        return containsAny(message, "어떤 조건", "조건으로", "조건일 때", "채용 공고 변화");
    }

    private Optional<String> parseRecruitmentKeywordAnswer(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }

        String candidate = value.trim();
        int conditionStart = firstRecruitmentConditionIndex(candidate);
        if (conditionStart >= 0) {
            // "백엔드 전체 5건 이상 변동"처럼 대상과 조건이 한 문장에 함께 온 경우 앞부분만 검색어로 쓴다.
            candidate = candidate.substring(0, conditionStart);
        }

        String keyword = cleanRecruitmentKeywordAnswer(candidate)
                .replace("전체", "")
                .replace("전부", "")
                .replace("모든", "")
                .replace("진행중", "")
                .replace("진행 중", "")
                .replaceAll("\\s+", " ")
                .strip();

        for (String prefix : List.of("공공기관 ", "공기업 ", "공공 ", "기관 ", "워크넷 ")) {
            if (keyword.startsWith(prefix)) {
                keyword = keyword.substring(prefix.length()).strip();
                break;
            }
        }

        return isBlank(keyword) ? Optional.empty() : Optional.of(keyword);
    }

    private int firstRecruitmentConditionIndex(String value) {
        int first = value.length();
        Matcher count = RECRUITMENT_COUNT_THRESHOLD.matcher(value);
        if (count.find()) {
            first = Math.min(first, count.start());
        }
        for (String marker : List.of(
                "새 공고",
                "신규 공고",
                "진행중 공고",
                "진행 중 공고",
                "새로",
                "뜨면",
                "올라오면",
                "등록",
                "늘면",
                "증가",
                "감소",
                "줄면",
                "줄어",
                "마감",
                "변동",
                "변화"
        )) {
            int index = value.indexOf(marker);
            if (index >= 0) {
                first = Math.min(first, index);
            }
        }
        return first == value.length() ? -1 : first;
    }

    private Optional<Map<String, String>> parseRecruitmentConditionAnswer(String value) {
        String text = value == null ? "" : value.trim();
        Matcher count = RECRUITMENT_COUNT_THRESHOLD.matcher(text);
        boolean hasCountThreshold = count.find();
        if (!hasCountThreshold && !containsRecruitmentChangeWord(text)) {
            return Optional.empty();
        }

        // 숫자가 없는 "새 공고가 올라오면" 계열은 최소 1건 증가 조건으로 해석한다.
        String threshold = hasCountThreshold ? normalizeDecimal(count.group(1)) : "1";
        return Optional.of(Map.of(
                "conditionMetric", text.contains("진행중") || text.contains("진행 중") ? "ONGOING_COUNT" : "COUNT",
                "conditionDirection", recruitmentConditionDirection(text),
                "conditionOperator", recruitmentConditionOperator(text),
                "conditionThreshold", threshold,
                "conditionUnit", "COUNT"
        ));
    }

    private boolean containsRecruitmentChangeWord(String text) {
        return containsAny(
                text,
                "새 공고",
                "신규",
                "새로",
                "뜨면",
                "올라오면",
                "등록",
                "변화",
                "변동",
                "늘면",
                "증가",
                "마감",
                "감소",
                "줄면",
                "줄어"
        );
    }

    private String recruitmentConditionDirection(String text) {
        if (containsAny(text, "감소", "줄면", "줄어", "마감")) {
            return "DOWN";
        }
        if (containsAny(text, "새 공고", "신규", "새로", "뜨면", "올라오면", "등록", "늘면", "증가")) {
            return "UP";
        }
        if (containsAny(text, "변동", "변화")) {
            return "ANY";
        }
        return "UP";
    }

    private String recruitmentConditionOperator(String text) {
        if (text.contains("미만")) {
            return "LT";
        }
        if (text.contains("이하")) {
            return "LTE";
        }
        if (text.contains("초과")) {
            return "GT";
        }
        return "GTE";
    }

    private String normalizeDecimal(String raw) {
        return new BigDecimal(raw).stripTrailingZeros().toPlainString();
    }

    private void ensureRecruitmentDefaults(Map<String, String> params) {
        String toolName = params.getOrDefault("dataToolName", "search_public_job");
        params.put("dataToolName", toolName);
        if ("search_public_job".equals(toolName)) {
            params.putIfAbsent("page_no", "1");
            params.putIfAbsent("num_of_rows", "20");
            params.putIfAbsent("ongoing_yn", "Y");
            String keyword = params.get("keyword");
            if (!isBlank(keyword)) {
                params.put("recrut_pbanc_ttl", keyword);
            }
        } else if ("search_worknet_job".equals(toolName)) {
            params.putIfAbsent("start_page", "1");
            params.putIfAbsent("display", "20");
        }
    }

    private List<String> missingPersistedFields(SubscriptionConversationJpaEntity conversation) {
        List<String> missing = new ArrayList<>();
        if (conversation.getDraftNotificationChannel() == null) {
            missing.add("notificationChannel");
        }
        if (conversation.getDraftNotificationChannel() == NotificationChannel.EMAIL
                && isBlank(conversation.getDraftNotificationTargetAddress())
                && loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(conversation.getUserId(), NotificationChannel.EMAIL).isEmpty()) {
            missing.add("emailAddress");
        }
        if (conversation.getDraftNotificationChannel() != null
                && conversation.getDraftNotificationChannel() != NotificationChannel.EMAIL
                && loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(
                        conversation.getUserId(),
                        conversation.getDraftNotificationChannel()
                ).isEmpty()) {
            missing.add("notificationEndpoint");
        }
        if (requiresApartmentDealType(conversation)) {
            missing.add("dealType");
        }
        Map<String, String> monitoringParams = monitoringParams(conversation.getDraftMonitoringParams());
        // 저장된 draft에는 MCP missingFields가 남지 않으므로 채용 필수 키워드를 다시 검증한다.
        if (isRecruitmentKeywordConfirmationPending(monitoringParams)) {
            missing.add(KEYWORD_CONFIRMATION);
        }
        if (isRecruitmentDraft(conversation) && isBlank(monitoringParams.get("keyword"))) {
            missing.add("keyword");
        }
        if (StructuredCondition.fromParameters(monitoringParams).isEmpty()) {
            missing.add("condition");
        }
        if (conversation.getDraftDomainId() == null
                || isBlank(conversation.getDraftQuery())
                || isBlank(conversation.getDraftIntent())) {
            missing.add("draft");
        }
        // mcpTool 누락 체크 제거 — MCP tool 선택은 Spring AI(MCP server)가 담당
        // if (conversation.getDraftDomainId() != null && isBlank(conversation.getDraftToolName())) {
        //     missing.add("mcpTool");
        // }
        return missing;
    }

    private void ensureInternalCheckCron(SubscriptionConversationJpaEntity conversation) {
        if (isBlank(conversation.getDraftCronExpr())) {
            conversation.updateCadence(DEFAULT_INTERNAL_CHECK_CRON);
        }
    }

    private List<ActionOption> channelActions(Long userId) {
        return List.of(
                channelAction(userId, NotificationChannel.TELEGRAM_DM, "Telegram"),
                channelAction(userId, NotificationChannel.DISCORD_DM, "Discord"),
                channelAction(userId, NotificationChannel.EMAIL, "Email")
        );
    }

    private ActionOption channelAction(Long userId, NotificationChannel channel, String label) {
        boolean connected = loadNotificationEndpointPort.loadEnabledByUserIdAndChannel(userId, channel).isPresent();
        return new ActionOption("SELECT_CHANNEL", label, channel.name(), connected, channel != NotificationChannel.EMAIL);
    }

    private Response needsInput(
            SubscriptionConversationJpaEntity conversation,
            String assistantMessage,
            List<ActionOption> actions
    ) {
        return new Response(conversation.getId(), "NEEDS_INPUT", assistantMessage, null, actions, null);
    }

    private Response readyForConfirmation(SubscriptionConversationJpaEntity conversation) {
        return new Response(
                conversation.getId(),
                "READY_FOR_CONFIRMATION",
                confirmationMessage(),
                draftView(conversation),
                List.of(
                        new ActionOption("CONFIRM_SUBSCRIPTION", "알림 시작", "confirm", true, false),
                        new ActionOption("CANCEL_CONVERSATION", "취소", "cancel", true, false)
                ),
                null
        );
    }

    private DraftView draftView(SubscriptionConversationJpaEntity conversation) {
        return new DraftView(
                conversation.getDraftQuery(),
                conversation.getDraftDomainId(),
                domainLabel(conversation.getDraftDomainName()),
                conversation.getDraftIntent(),
                conversation.getDraftToolName(),
                monitoringParams(conversation.getDraftMonitoringParams()),
                conversation.getDraftCronExpr(),
                cadenceLabel(conversation.getDraftCronExpr()),
                conversation.getDraftNotificationChannel(),
                channelLabel(conversation.getDraftNotificationChannel()),
                recipientLabel(conversation)
        );
    }

    private Map<String, String> monitoringParams(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Optional<NotificationChannel> parseChannel(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("TELEGRAM".equals(normalized)) {
            normalized = "TELEGRAM_DM";
        }
        if ("DISCORD".equals(normalized)) {
            normalized = "DISCORD_DM";
        }
        try {
            return Optional.of(NotificationChannel.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<NotificationChannel> parseChannelAnswer(String value) {
        Optional<NotificationChannel> exact = parseChannel(value);
        if (exact.isPresent()) {
            return exact;
        }

        String text = lower(value);
        if (text.contains("텔레그램") || text.contains("telegram")) {
            return Optional.of(NotificationChannel.TELEGRAM_DM);
        }
        if (text.contains("디스코드") || text.contains("디코") || text.contains("discord")) {
            return Optional.of(NotificationChannel.DISCORD_DM);
        }
        if (text.contains("이메일") || text.contains("메일") || text.contains("email")) {
            return Optional.of(NotificationChannel.EMAIL);
        }
        return Optional.empty();
    }

    private String questionForMissing(List<String> missing, SubscriptionConversationJpaEntity conversation) {
        if (missing.contains(KEYWORD_CONFIRMATION)) {
            Map<String, String> params = monitoringParams(conversation.getDraftMonitoringParams());
            String keyword = params.getOrDefault(KEYWORD_ORIGINAL, params.get("keyword"));
            String suggestedKeyword = params.get(SUGGESTED_KEYWORD);
            return "현재 '" + keyword + "' 검색 결과가 없어요. '" + suggestedKeyword
                    + "'를 뜻한 걸까요? 맞으면 '응', 아니면 원하는 채용 검색어를 다시 입력해 주세요.";
        }
        if (missing.contains("keyword") && isRecruitmentDraft(conversation)) {
            return "어떤 채용 공고를 구독할까요? 예: 백엔드, 데이터, 공공기관 인턴 등";
        }
        if (missing.contains("dealType")) {
            return "부동산 가격은 매매/전월세 중 어떤 기준인가요?";
        }
        if (missing.contains("condition")) {
            if (isRecruitmentDraft(conversation)) {
                return "어떤 채용 공고 변화가 생기면 알림을 받을까요? 예: 새 공고 1건 이상 등록 등";
            }
            return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등";
        }
        if (missing.contains("notificationChannel")) {
            return "알림을 받을 채널을 선택해 주세요. Telegram, Discord, Email 중 무엇으로 받을까요?";
        }
        if (missing.contains("emailAddress")) {
            return "알림을 받을 이메일 주소를 입력해 주세요.";
        }
        if (missing.contains("notificationEndpoint")) {
            return channelLabel(conversation.getDraftNotificationChannel()) + " 연결이 필요합니다.";
        }
        if (missing.contains("mcpTool")) {
            return "알림 도구 설정이 준비되지 않았어요. 잠시 후 다시 시도해 주세요.";
        }
        return "알림을 만들기 위해 필요한 정보가 더 필요해요.";
    }

    private String domainLabel(String domainName) {
        return switch (domainName == null ? "" : domainName) {
            case "real-estate" -> "부동산";
            case "law-regulation" -> "법률/규제";
            case "recruitment" -> "채용";
            case "auction" -> "경매/희소매물";
            default -> domainName;
        };
    }

    private String channelLabel(NotificationChannel channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case TELEGRAM_DM -> "Telegram";
            case DISCORD_DM -> "Discord";
            case EMAIL -> "Email";
        };
    }

    private String cadenceLabel(String cronExpr) {
        return "변화 감지 시";
    }

    private String recipientLabel(SubscriptionConversationJpaEntity conversation) {
        if (conversation.getDraftNotificationChannel() == NotificationChannel.EMAIL
                && !isBlank(conversation.getDraftNotificationTargetAddress())) {
            return conversation.getDraftNotificationTargetAddress();
        }
        return "연결된 " + channelLabel(conversation.getDraftNotificationChannel()) + " 계정";
    }

    private String confirmationMessage() {
        return "아래 내용으로 알림을 시작할까요?";
    }

    private boolean isEmail(String value) {
        return !isBlank(value) && EMAIL.matcher(value.trim()).matches();
    }

    private Response completeKeywordConfirmationIfPossible(
            SubscriptionConversationJpaEntity conversation,
            String message
    ) {
        Map<String, String> params = new HashMap<>(monitoringParams(conversation.getDraftMonitoringParams()));
        String suggestedKeyword = params.get(SUGGESTED_KEYWORD);
        if (isBlank(suggestedKeyword)) {
            return null;
        }

        Optional<Boolean> accepted = parseKeywordConfirmationAnswer(message);
        if (accepted.isPresent()) {
            if (accepted.get()) {
                return updateRecruitmentKeywordAndComplete(conversation, params, suggestedKeyword);
            }
            clearRecruitmentKeyword(params);
            updateRecruitmentParams(conversation, params, "채용 공고");
            return completeOrAsk(conversation);
        }

        String replacement = cleanRecruitmentKeywordAnswer(message);
        if (isBlank(replacement)) {
            return null;
        }
        return updateRecruitmentKeywordAndComplete(conversation, params, replacement);
    }

    private Response updateRecruitmentKeywordAndComplete(
            SubscriptionConversationJpaEntity conversation,
            Map<String, String> params,
            String keyword
    ) {
        clearRecruitmentKeywordValidation(params);
        params.put("keyword", keyword);
        if ("search_public_job".equals(params.get("dataToolName"))) {
            params.put("recrut_pbanc_ttl", keyword);
        }
        updateRecruitmentParams(conversation, params, keyword + " 채용 공고");
        return completeOrAsk(conversation);
    }

    private void updateRecruitmentParams(
            SubscriptionConversationJpaEntity conversation,
            Map<String, String> params,
            String query
    ) {
        conversation.updateParsedDraft(
                conversation.getParseSessionId(),
                query,
                conversation.getDraftDomainId(),
                conversation.getDraftDomainName(),
                conversation.getDraftIntent(),
                conversation.getDraftToolName(),
                toJson(params),
                conversation.getDraftCronExpr(),
                conversation.getDraftNotificationChannel(),
                conversation.getDraftNotificationTargetAddress(),
                conversation.getLastAssistantMessage(),
                conversation.getStatus()
        );
    }

    private void clearRecruitmentKeyword(Map<String, String> params) {
        params.remove("keyword");
        params.remove("recrut_pbanc_ttl");
        clearRecruitmentKeywordValidation(params);
    }

    private void clearRecruitmentKeywordValidation(Map<String, String> params) {
        params.remove(KEYWORD_VALIDATION_STATUS);
        params.remove(KEYWORD_ORIGINAL);
        params.remove(SUGGESTED_KEYWORD);
    }

    private void clearRecruitmentCondition(Map<String, String> params) {
        params.remove("condition");
        params.remove("conditionMetric");
        params.remove("conditionDirection");
        params.remove("conditionOperator");
        params.remove("conditionThreshold");
        params.remove("conditionUnit");
    }

    private Optional<Boolean> parseKeywordConfirmationAnswer(String value) {
        String text = lower(value).trim();
        if (text.isBlank()) {
            return Optional.empty();
        }
        if (text.equals("y")
                || text.equals("yes")
                || text.contains("응")
                || text.contains("네")
                || text.contains("맞")
                || text.contains("좋아")) {
            return Optional.of(true);
        }
        if (text.equals("n")
                || text.equals("no")
                || text.contains("아니")
                || text.contains("아님")
                || text.contains("틀려")) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private String cleanRecruitmentKeywordAnswer(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.trim()
                .replace("채용", "")
                .replace("공고", "")
                .replace("알려줘", "")
                .replace("구독", "")
                .replace("알림", "")
                .strip();
    }

    private Optional<String> parseDealTypeAnswer(String value) {
        String text = lower(value);
        if (text.contains("전월세") || text.contains("전세") || text.contains("월세")) {
            return Optional.of("RENT");
        }
        if (text.contains("매매") || text.contains("실거래가")) {
            return Optional.of("TRADE");
        }
        return Optional.empty();
    }

    private Response selectDealType(SubscriptionConversationJpaEntity conversation, String value) {
        RealEstateDraftSelection selection = realEstateDraftSelection(conversation, value);

        conversation.updateParsedDraft(
                conversation.getParseSessionId(),
                realEstateQuery(conversation, selection),
                conversation.getDraftDomainId(),
                conversation.getDraftDomainName(),
                selection.intent(),
                selection.toolName(),
                clearPendingDealTypeConfirmation(conversation.getDraftMonitoringParams()),
                conversation.getDraftCronExpr(),
                conversation.getDraftNotificationChannel(),
                conversation.getDraftNotificationTargetAddress(),
                conversation.getLastAssistantMessage(),
                conversation.getStatus()
        );
        return completeOrAsk(conversation);
    }

    private String apartmentTradeQuery(SubscriptionConversationJpaEntity conversation) {
        return realEstateQuery(conversation, new RealEstateDraftSelection(
                "apartment_trade_price",
                "search_house_price",
                "아파트 매매"
        ));
    }

    private String realEstateQuery(
            SubscriptionConversationJpaEntity conversation,
            RealEstateDraftSelection selection
    ) {
        if (!hasPendingDealTypeConfirmation(conversation)
                && !requiresExplicitApartmentDealType(conversation.getDraftQuery())) {
            return conversation.getDraftQuery();
        }
        String region = monitoringParams(conversation.getDraftMonitoringParams()).get("region");
        if (!isBlank(region)) {
            return region + " " + selection.label() + " 실거래가";
        }
        return selection.label() + " 실거래가";
    }

    private RealEstateDraftSelection realEstateDraftSelection(
            SubscriptionConversationJpaEntity conversation,
            String dealType
    ) {
        String assetType = realEstateAssetType(conversation);
        return switch (assetType + ":" + dealType) {
            case "officetel:RENT" -> new RealEstateDraftSelection(
                    "officetel_rent_price",
                    "search_offi_rent",
                    "오피스텔 전월세"
            );
            case "officetel:TRADE" -> new RealEstateDraftSelection(
                    "officetel_trade_price",
                    "search_offi_trade",
                    "오피스텔 매매"
            );
            case "row_house:RENT" -> new RealEstateDraftSelection(
                    "row_house_rent_price",
                    "search_rh_rent",
                    "연립다세대 전월세"
            );
            case "row_house:TRADE" -> new RealEstateDraftSelection(
                    "row_house_trade_price",
                    "search_rh_trade",
                    "연립다세대 매매"
            );
            case "apartment:RENT" -> new RealEstateDraftSelection(
                    "apartment_rent_price",
                    "search_apt_rent",
                    "아파트 전월세"
            );
            default -> new RealEstateDraftSelection(
                    "apartment_trade_price",
                    "search_house_price",
                    "아파트 매매"
            );
        };
    }

    private String realEstateAssetType(SubscriptionConversationJpaEntity conversation) {
        String toolName = lower(conversation.getDraftToolName());
        if (toolName.startsWith("search_offi")) {
            return "officetel";
        }
        if (toolName.startsWith("search_rh")) {
            return "row_house";
        }

        String intent = lower(conversation.getDraftIntent());
        if (intent.startsWith("officetel")) {
            return "officetel";
        }
        if (intent.startsWith("row_house")) {
            return "row_house";
        }

        String query = lower(conversation.getDraftQuery());
        if (query.contains("오피스텔")) {
            return "officetel";
        }
        if (containsAny(query, "연립다세대", "연립", "다세대", "빌라")) {
            return "row_house";
        }
        return "apartment";
    }

    private boolean requiresApartmentDealType(SubscriptionConversationJpaEntity conversation) {
        return "real-estate".equals(conversation.getDraftDomainName())
                && (hasPendingDealTypeConfirmation(conversation)
                        || requiresExplicitApartmentDealType(conversation.getDraftQuery()));
    }

    private boolean hasPendingDealTypeConfirmation(SubscriptionConversationJpaEntity conversation) {
        return "true".equalsIgnoreCase(monitoringParams(conversation.getDraftMonitoringParams())
                .get(PENDING_DEAL_TYPE_CONFIRMATION));
    }

    private String clearPendingDealTypeConfirmation(String paramsJson) {
        Map<String, String> params = new HashMap<>(monitoringParams(paramsJson));
        params.remove(PENDING_DEAL_TYPE_CONFIRMATION);
        return toJson(params);
    }

    private boolean requiresExplicitApartmentDealType(String value) {
        String text = lower(value);
        if (text.contains("전월세") || text.contains("전세") || text.contains("월세") || text.contains("매매")) {
            return false;
        }
        return text.contains("아파트")
                || text.contains("오피스텔")
                || text.contains("연립")
                || text.contains("다세대")
                || text.contains("빌라")
                || text.contains("가격")
                || text.contains("시세")
                || text.contains("집값")
                || text.contains("실거래가")
                || text.contains("변경")
                || text.contains("변동");
    }

    private boolean isRealEstateDraft(SubscriptionConversationJpaEntity conversation) {
        return "real-estate".equals(conversation.getDraftDomainName());
    }

    private boolean isRecruitmentDraft(SubscriptionConversationJpaEntity conversation) {
        return "recruitment".equals(conversation.getDraftDomainName());
    }

    private Optional<String> explicitDomainFromMessage(String message) {
        String text = lower(message);
        if (isBlank(text)) {
            return Optional.empty();
        }
        int recruitmentIndex = Math.max(
                lastIndexOfAny(text, "채용", "구인", "일자리", "워크넷"),
                lastIndexOfEnglishWord(text, "job", "jobs", "recruitment", "recruit", "worknet")
        );
        int realEstateIndex = lastIndexOfAny(text, "부동산", "아파트", "매매", "전세", "월세", "실거래가", "집값", "real estate", "apartment");
        if (recruitmentIndex < 0 && realEstateIndex < 0) {
            return Optional.empty();
        }
        if (recruitmentIndex > realEstateIndex) {
            return Optional.of("recruitment");
        }
        return Optional.of("real-estate");
    }

    private boolean allowsDomainSwitch(
            SubscriptionConversationJpaEntity conversation,
            String requestedDomain,
            String message
    ) {
        if (isRecruitmentDraft(conversation)
                && "real-estate".equals(requestedDomain)
                && isRecruitmentKeywordConfirmationPending(monitoringParams(conversation.getDraftMonitoringParams()))) {
            return isStrongRealEstateRequest(message);
        }
        return true;
    }

    private boolean isStrongRealEstateRequest(String message) {
        String text = lower(message);
        return containsAny(text, "부동산", "실거래가", "집값", "real estate", "house price")
                || (containsAny(text, "아파트")
                        && containsAny(text, "매매", "전세", "월세", "가격", "시세", "변동", "변경"));
    }

    private int lastIndexOfAny(String value, String... tokens) {
        if (isBlank(value)) {
            return -1;
        }
        int index = -1;
        for (String token : tokens) {
            index = Math.max(index, value.lastIndexOf(token));
        }
        return index;
    }

    private int lastIndexOfEnglishWord(String value, String... words) {
        if (isBlank(value)) {
            return -1;
        }
        int index = -1;
        for (String word : words) {
            java.util.regex.Matcher matcher = Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                    .matcher(value);
            while (matcher.find()) {
                index = Math.max(index, matcher.start());
            }
        }
        return index;
    }

    private boolean isRecruitmentKeywordConfirmationPending(Map<String, String> monitoringParams) {
        return ZERO_RESULTS.equals(monitoringParams.get(KEYWORD_VALIDATION_STATUS))
                && !isBlank(monitoringParams.get(SUGGESTED_KEYWORD));
    }

    private boolean containsAny(String value, String... candidates) {
        String text = value == null ? "" : value;
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String emptyIfBlank(String value) {
        return isBlank(value) ? "" : value;
    }

    private record RealEstateDraftSelection(String intent, String toolName, String label) {
    }

    public record ActionRequest(String type, String value) {
    }

    public record Response(
            String conversationId,
            String status,
            String assistantMessage,
            DraftView draft,
            List<ActionOption> actions,
            CreatedSubscription subscription
    ) {
    }

    public record ActionOption(
            String type,
            String label,
            String value,
            boolean connected,
            boolean requiresConnection
    ) {
    }

    public record DraftView(
            String query,
            Long domainId,
            String domainLabel,
            String intent,
            String toolName,
            Map<String, String> monitoringParams,
            String cronExpr,
            String cadenceLabel,
            NotificationChannel notificationChannel,
            String channelLabel,
            String recipientLabel
    ) {
    }

    public record CreatedSubscription(String id, java.time.LocalDateTime nextRun) {
    }
}
