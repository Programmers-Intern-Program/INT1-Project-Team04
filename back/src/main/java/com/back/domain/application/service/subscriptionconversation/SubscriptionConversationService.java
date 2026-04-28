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
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.application.result.SubscriptionResult;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionConversationService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PERCENT_THRESHOLD = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|퍼센트|프로)?");
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

        Response percentConditionResponse = completePercentConditionIfPossible(conversation, message);
        if (percentConditionResponse != null) {
            return percentConditionResponse;
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
        draft = withStoredMcpTool(draft, domainId);
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
            case "SELECT_CADENCE" -> selectCadence(conversation, action.value());
            case "SELECT_CHANNEL" -> selectChannel(conversation, action.value());
            case "CONFIRM_SUBSCRIPTION" -> confirm(userId, conversation);
            case "CANCEL_CONVERSATION" -> cancel(conversation);
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST);
        };
    }

    private Response selectCadence(SubscriptionConversationJpaEntity conversation, String value) {
        conversation.updateCadence(cadenceCron(value));
        return completeOrAsk(conversation);
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
        resolveMissingMcpTool(conversation);
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

        resolveMissingMcpTool(conversation);
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
        monitoringConfigRepository.save(new SubscriptionMonitoringConfigJpaEntity(
                result.id(),
                conversation.getDraftToolName(),
                conversation.getDraftIntent(),
                conversation.getDraftMonitoringParams()
        ));
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
                && !isBlank(draft.toolName())
                && !isBlank(draft.monitoringParams().get("condition"))
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
        if (missing.contains("cadence")) {
            return cadenceActions();
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

    private Response completePercentConditionIfPossible(
            SubscriptionConversationJpaEntity conversation,
            String message
    ) {
        if (conversation.getStatus() != SubscriptionConversationStatus.COLLECTING
                || isBlank(conversation.getLastAssistantMessage())
                || !conversation.getLastAssistantMessage().contains("%")) {
            return null;
        }

        Matcher matcher = PERCENT_THRESHOLD.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return null;
        }

        Map<String, String> monitoringParams = new HashMap<>(monitoringParams(conversation.getDraftMonitoringParams()));
        monitoringParams.put("condition", percentCondition(message, matcher.group(1)));
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

    private Response completeShortAnswerIfPossible(
            SubscriptionConversationJpaEntity conversation,
            String message
    ) {
        List<String> missing = missingPersistedFields(conversation);
        if (missing.contains("notificationChannel")) {
            Optional<NotificationChannel> channel = parseChannelAnswer(message);
            if (channel.isPresent()) {
                return selectChannel(conversation, channel.get().name());
            }
        }

        if (missing.contains("cadence")) {
            Optional<String> cadence = parseCadenceAnswer(message);
            if (cadence.isPresent()) {
                return selectCadence(conversation, cadence.get());
            }
        }

        return null;
    }

    private String percentCondition(String message, String threshold) {
        String text = message == null ? "" : message;
        if (text.contains("하락") || text.contains("떨어") || text.contains("내리")) {
            return threshold + "% 이상 하락";
        }
        if (text.contains("상승") || text.contains("오르") || text.contains("올라")) {
            return threshold + "% 이상 상승";
        }
        return threshold + "% 이상 변동";
    }

    private List<String> missingPersistedFields(SubscriptionConversationJpaEntity conversation) {
        List<String> missing = new ArrayList<>();
        if (isBlank(conversation.getDraftCronExpr())) {
            missing.add("cadence");
        }
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
        if (isBlank(monitoringParams(conversation.getDraftMonitoringParams()).get("condition"))) {
            missing.add("condition");
        }
        if (conversation.getDraftDomainId() == null
                || isBlank(conversation.getDraftQuery())
                || isBlank(conversation.getDraftIntent())) {
            missing.add("draft");
        }
        if (conversation.getDraftDomainId() != null && isBlank(conversation.getDraftToolName())) {
            missing.add("mcpTool");
        }
        return missing;
    }

    private List<ActionOption> cadenceActions() {
        return List.of(
                new ActionOption("SELECT_CADENCE", "매시간", "HOURLY", true, false),
                new ActionOption("SELECT_CADENCE", "매일 오전 9시", "DAILY_9AM", true, false),
                new ActionOption("SELECT_CADENCE", "평일 오전 9시", "WEEKDAY_9AM", true, false)
        );
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

    private Optional<String> parseCadenceAnswer(String value) {
        String text = lower(value);
        if (text.contains("매시간") || text.contains("한 시간") || text.contains("1시간")) {
            return Optional.of("HOURLY");
        }
        if (text.contains("평일")) {
            return Optional.of("WEEKDAY_9AM");
        }
        if (text.contains("매일") || text.contains("아침") || text.contains("오전 9")) {
            return Optional.of("DAILY_9AM");
        }
        return Optional.empty();
    }

    private String cadenceCron(String value) {
        return switch (value) {
            case "HOURLY", "0 0 * * * *" -> "0 0 * * * *";
            case "DAILY_9AM", "0 0 9 * * *" -> "0 0 9 * * *";
            case "WEEKDAY_9AM", "0 0 9 * * MON-FRI" -> "0 0 9 * * MON-FRI";
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST);
        };
    }

    private String questionForMissing(List<String> missing, SubscriptionConversationJpaEntity conversation) {
        if (missing.contains("condition")) {
            return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등";
        }
        if (missing.contains("cadence")) {
            return "얼마나 자주 확인할까요?";
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
        return switch (cronExpr == null ? "" : cronExpr) {
            case "0 0 * * * *" -> "매시간";
            case "0 0 9 * * *" -> "매일 오전 9시";
            case "0 0 9 * * MON-FRI" -> "평일 오전 9시";
            default -> cronExpr;
        };
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

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
