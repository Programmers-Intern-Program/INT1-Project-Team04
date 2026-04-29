package com.back.domain.application.service;

import com.back.domain.application.port.in.RunDueSchedulesUseCase;
import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.port.out.LoadDueSchedulesPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.port.out.LoadRecentAiDataHubPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.application.port.out.SaveNotificationPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SendNotificationPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.application.service.monitoring.McpSnapshotEnvelope;
import com.back.domain.application.service.monitoring.MonitoringAlertMessageBuilder;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import com.back.domain.application.service.monitoring.MonitoringChangeDetector;
import com.back.domain.application.service.monitoring.MonitoringQueryMatcher;
import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSource;
import com.back.domain.model.notification.Notification;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.notification.NotificationStatus;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * [Domain Service] 예약된 스케줄에 따라 AI 분석 및 알림을 총괄하는 Service
 * * 비즈니스 로직의 오케스트레이터 역할
 */
@Service
@RequiredArgsConstructor
public class ScheduleExecutionService implements RunDueSchedulesUseCase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() {};
    private static final int RECENT_SNAPSHOT_LIMIT = 20;
    private static final Pattern REGION = Pattern.compile(
            "([가-힣]+(?:특별자치시|특별자치도|특별시|광역시|시|군|구)|서울|부산|대구|인천|광주|대전|울산|세종|제주)"
    );

    private final LoadDueSchedulesPort loadDueSchedulesPort;
    private final LoadMcpToolPort loadMcpToolPort;
    private final LoadSubscriptionMonitoringConfigPort loadSubscriptionMonitoringConfigPort;
    private final LoadEnabledNotificationPreferencePort loadEnabledNotificationPreferencePort;
    private final ExecuteMcpToolPort executeMcpToolPort;
    private final SaveAiDataHubPort saveAiDataHubPort;
    private final LoadRecentAiDataHubPort loadRecentAiDataHubPort;
    private final SaveNotificationPort saveNotificationPort;
    private final SendNotificationPort sendNotificationPort;
    private final SaveSchedulePort saveSchedulePort;
    private final MonitoringQueryMatcher monitoringQueryMatcher;
    private final MonitoringChangeDetector monitoringChangeDetector;
    private final MonitoringAlertMessageBuilder monitoringAlertMessageBuilder;
    private final GenerateMonitoringBriefingPort generateMonitoringBriefingPort;
    private final NotificationDeliveryCreationService notificationDeliveryCreationService;

    @Override
    public void runDueSchedules() {
        LocalDateTime now = LocalDateTime.now();
        for (Schedule schedule : loadDueSchedulesPort.loadDueSchedules(now)) {
            executeSchedule(schedule, now);
        }
    }

    private void executeSchedule(Schedule schedule, LocalDateTime now) {
        Optional<SubscriptionMonitoringConfig> monitoringConfig =
                loadSubscriptionMonitoringConfigPort.loadBySubscriptionId(schedule.subscription().id());
        McpTool tool = loadConfiguredTool(schedule, monitoringConfig)
                .or(() -> loadMcpToolPort.loadByDomainId(schedule.subscription().domain().id()))
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_TOOL_NOT_FOUND));
        Map<String, Object> parameters = monitoringConfig
                .map(this::parameters)
                .orElseGet(() -> fallbackParameters(schedule.subscription().query()));

        McpExecutionResult result = executeMcpToolPort.execute(
                tool,
                executionArguments(tool, parameters, now)
        );

        Optional<McpSnapshotEnvelope> currentSnapshot = McpSnapshotEnvelope.parseIfSummaryPresent(OBJECT_MAPPER, result.metadata());
        Optional<McpSnapshotEnvelope> previousSnapshot = currentSnapshot.flatMap(snapshot -> previousSnapshot(schedule, tool, snapshot));

        AiDataHub aiDataHub = saveAiDataHubPort.save(new AiDataHub(
                UuidGenerator.create(),
                schedule.subscription().user(),
                tool,
                result.apiType(),
                result.content(),
                null,
                metadataWithExecutionContext(result.metadata(), schedule),
                now
        ));

        if (currentSnapshot.isEmpty()) {
            saveAndSendNotification(schedule, aiDataHub, result.content(), now);
            advanceSchedule(schedule, now);
            return;
        }

        McpSnapshotEnvelope snapshot = currentSnapshot.get();
        previousSnapshot.ifPresent(previous -> {
            MonitoringChangeDecision decision = monitoringChangeDetector.detect(
                    previous.summary(),
                    snapshot.summary(),
                    stringParameters(parameters)
            );
            if (decision.triggered()) {
                sendAlertNotification(schedule, tool, aiDataHub, result, previous, snapshot, decision, now);
            }
        });

        advanceSchedule(schedule, now);
    }

    private void sendAlertNotification(
            Schedule schedule,
            McpTool tool,
            AiDataHub aiDataHub,
            McpExecutionResult result,
            McpSnapshotEnvelope previousSnapshot,
            McpSnapshotEnvelope currentSnapshot,
            MonitoringChangeDecision decision,
            LocalDateTime now
    ) {
        String fallbackMessage = monitoringAlertMessageBuilder.build(
                schedule.subscription().query(),
                tool.name(),
                decision,
                result.content()
        );
        String message = generateMonitoringBriefingPort.generate(new MonitoringBriefingRequest(
                        schedule.subscription().query(),
                        tool.name(),
                        decision,
                        previousSnapshot.summary().toString(),
                        currentSnapshot.summary().toString(),
                        result.content()
                ))
                .filter(briefing -> !briefing.isBlank())
                .orElse(fallbackMessage);
        saveAndSendNotification(schedule, aiDataHub, message, now);
    }

    private void saveAndSendNotification(
            Schedule schedule,
            AiDataHub aiDataHub,
            String message,
            LocalDateTime now
    ) {
        Notification pending = saveNotificationPort.save(new Notification(
                UuidGenerator.create(),
                schedule,
                schedule.subscription().user(),
                aiDataHub,
                notificationChannel(schedule.subscription().id()),
                message,
                null,
                NotificationStatus.PENDING
        ));

        boolean sent = sendNotificationPort.send(pending);
        notificationDeliveryCreationService.createFor(alertEvent(schedule, message, now));
        saveNotificationPort.save(new Notification(
                pending.id(),
                pending.schedule(),
                pending.user(),
                pending.aiDataHub(),
                pending.channel(),
                pending.message(),
                sent ? now : null,
                sent ? NotificationStatus.SENT : NotificationStatus.FAILED
        ));
    }

    private AlertEvent alertEvent(Schedule schedule, String message, LocalDateTime now) {
        String title = notificationTitle(message);
        String summary = notificationSummary(message);
        return new AlertEvent(
                UuidGenerator.create(),
                schedule.subscription(),
                title,
                summary,
                "기존 스냅샷과 최신 MCP 응답을 비교해 구독 조건에 맞는 변화가 감지되었습니다.",
                List.of(new AlertSource(title, null, summary)),
                now
        );
    }

    private String notificationTitle(String message) {
        String title = message == null
                ? ""
                : message.lines()
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElse("");
        title = stripPrefix(title.strip(), "[AI 변화 브리핑]");
        title = stripPrefix(title.strip(), "[변화 감지]");
        return isBlank(title) ? "변화 감지 알림" : title;
    }

    private String notificationSummary(String message) {
        return isBlank(message) ? "구독 조건에 맞는 변화가 감지되었습니다." : message.strip();
    }

    private String stripPrefix(String value, String prefix) {
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length()).strip();
        }
        return value;
    }

    private void advanceSchedule(Schedule schedule, LocalDateTime now) {
        saveSchedulePort.save(new Schedule(
                schedule.id(),
                schedule.subscription(),
                schedule.cronExpr(),
                now,
                CronScheduleCalculator.nextRun(schedule.cronExpr(), now)
        ));
    }

    private Optional<McpSnapshotEnvelope> previousSnapshot(
            Schedule schedule,
            McpTool tool,
            McpSnapshotEnvelope currentSnapshot
    ) {
        Long userId = schedule.subscription().user() == null ? null : schedule.subscription().user().id();
        Long toolId = tool.id();
        if (userId == null || toolId == null) {
            return Optional.empty();
        }
        return loadRecentAiDataHubPort.loadRecentByUserIdAndToolId(userId, toolId, RECENT_SNAPSHOT_LIMIT).stream()
                .map(AiDataHub::metadata)
                .map(this::parseCandidateSnapshot)
                .flatMap(Optional::stream)
                .filter(snapshot -> Objects.equals(schedule.subscription().id(), snapshot.subscriptionIdOrNull()))
                .filter(snapshot -> monitoringQueryMatcher.sameTarget(snapshot.query(), currentSnapshot.query()))
                .findFirst();
    }

    private Optional<McpSnapshotEnvelope> parseCandidateSnapshot(String metadata) {
        try {
            return Optional.of(McpSnapshotEnvelope.parse(OBJECT_MAPPER, metadata));
        } catch (ApiException exception) {
            return Optional.empty();
        }
    }

    private String metadataWithExecutionContext(String metadataJson, Schedule schedule) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(metadataJson == null ? "" : metadataJson);
            if (!parsed.isObject()) {
                throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
            }
            ObjectNode root = (ObjectNode) parsed;
            ObjectNode execution = root.putObject("execution");
            execution.put("subscription_id", schedule.subscription().id());
            execution.put("schedule_id", schedule.id());
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    private Optional<McpTool> loadConfiguredTool(
            Schedule schedule,
            Optional<SubscriptionMonitoringConfig> monitoringConfig
    ) {
        return monitoringConfig
                .filter(config -> !isBlank(config.toolName()))
                .flatMap(config -> loadMcpToolPort.loadByDomainIdAndName(
                        schedule.subscription().domain().id(),
                        config.toolName()
                ));
    }

    private Map<String, Object> executionArguments(
            McpTool tool,
            Map<String, Object> parameters,
            LocalDateTime now
    ) {
        if (tool.name().startsWith("search_house_price")) {
            return SearchHousePriceMcpInput.from(parameters, now).toArguments();
        }
        return parameters;
    }

    private Map<String, Object> parameters(SubscriptionMonitoringConfig config) {
        if (isBlank(config.parametersJson())) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(config.parametersJson(), PARAMETER_MAP);
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    private Map<String, Object> fallbackParameters(String query) {
        String region = extractRegion(query);
        if (isBlank(region)) {
            return Map.of();
        }
        return Map.of("region", region);
    }

    private Map<String, String> stringParameters(Map<String, Object> parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (value != null) {
                values.put(key, String.valueOf(value));
            }
        });
        return values;
    }

    private String extractRegion(String query) {
        Matcher matcher = REGION.matcher(query == null ? "" : query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String notificationChannel(String subscriptionId) {
        return loadEnabledNotificationPreferencePort.loadEnabledBySubscriptionId(subscriptionId).stream()
                .filter(NotificationPreference::enabled)
                .findFirst()
                .map(NotificationPreference::channel)
                .map(Enum::name)
                .orElse("DISCORD");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
