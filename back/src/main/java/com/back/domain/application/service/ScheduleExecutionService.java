package com.back.domain.application.service;

import com.back.domain.application.port.in.RunDueSchedulesUseCase;
import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.LoadDueSchedulesPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.application.port.out.SaveNotificationPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SendNotificationPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.mcp.McpTool;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Pattern REGION = Pattern.compile(
            "([가-힣]+(?:특별자치시|특별자치도|특별시|광역시|시|군|구)|서울|부산|대구|인천|광주|대전|울산|세종|제주)"
    );

    private final LoadDueSchedulesPort loadDueSchedulesPort;
    private final LoadMcpToolPort loadMcpToolPort;
    private final LoadSubscriptionMonitoringConfigPort loadSubscriptionMonitoringConfigPort;
    private final LoadEnabledNotificationPreferencePort loadEnabledNotificationPreferencePort;
    private final ExecuteMcpToolPort executeMcpToolPort;
    private final SaveAiDataHubPort saveAiDataHubPort;
    private final SaveNotificationPort saveNotificationPort;
    private final SendNotificationPort sendNotificationPort;
    private final SaveSchedulePort saveSchedulePort;

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

        McpExecutionResult result = executeMcpToolPort.execute(
                tool,
                executionArguments(tool, schedule.subscription().query(), monitoringConfig, now)
        );

        AiDataHub aiDataHub = saveAiDataHubPort.save(new AiDataHub(
                UuidGenerator.create(),
                schedule.subscription().user(),
                tool,
                result.apiType(),
                result.content(),
                null,
                result.metadata(),
                now
        ));

        Notification pending = saveNotificationPort.save(new Notification(
                UuidGenerator.create(),
                schedule,
                schedule.subscription().user(),
                aiDataHub,
                notificationChannel(schedule.subscription().id()),
                result.content(),
                null,
                NotificationStatus.PENDING
        ));

        boolean sent = sendNotificationPort.send(pending);
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

        saveSchedulePort.save(new Schedule(
                schedule.id(),
                schedule.subscription(),
                schedule.cronExpr(),
                now,
                CronScheduleCalculator.nextRun(schedule.cronExpr(), now)
        ));
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
            String query,
            Optional<SubscriptionMonitoringConfig> monitoringConfig,
            LocalDateTime now
    ) {
        Map<String, Object> parameters = monitoringConfig
                .map(this::parameters)
                .orElseGet(() -> fallbackParameters(query));
        if (tool.name().startsWith("search_house_price")) {
            return searchHousePriceArguments(parameters, now);
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

    private Map<String, Object> searchHousePriceArguments(Map<String, Object> parameters, LocalDateTime now) {
        Map<String, Object> input = new LinkedHashMap<>();
        String region = stringValue(parameters.get("region"));
        if (!isBlank(region)) {
            input.put("region", region);
        }
        input.put("deal_ymd", dealYmd(parameters, now));
        return Map.of("input", input);
    }

    private String dealYmd(Map<String, Object> parameters, LocalDateTime now) {
        String dealYmd = stringValue(parameters.get("deal_ymd"));
        if (!isBlank(dealYmd)) {
            return dealYmd;
        }
        dealYmd = stringValue(parameters.get("dealYmd"));
        if (!isBlank(dealYmd)) {
            return dealYmd;
        }
        return now.minusMonths(1).format(DEAL_YMD_FORMATTER);
    }

    private String extractRegion(String query) {
        Matcher matcher = REGION.matcher(query == null ? "" : query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
