package com.back.domain.application.service;

import com.back.domain.application.port.in.RunSubscriptionMonitorUseCase;
import com.back.domain.application.port.out.LoadDueSchedulesPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadNotificationEndpointPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationEndpoint;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * [Domain Service] due 스케줄을 조회해 Spring AI(MCP server)에 구독 실행 위임.
 *
 * MCP 호출 · 변화 감지 · 알림 발송은 MCP server 책임.
 * Spring Boot는 context 조립 + nextRun 업데이트만 담당.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionMonitorService implements RunSubscriptionMonitorUseCase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() {};
    private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Pattern REGION = Pattern.compile(
            "([가-힣]+(?:특별자치시|특별자치도|특별시|광역시|시|군|구)|서울|부산|대구|인천|광주|대전|울산|세종|제주)"
    );

    private final LoadDueSchedulesPort loadDueSchedulesPort;
    private final LoadSubscriptionMonitoringConfigPort loadSubscriptionMonitoringConfigPort;
    private final LoadEnabledNotificationPreferencePort loadEnabledNotificationPreferencePort;
    private final LoadNotificationEndpointPort loadNotificationEndpointPort;
    private final SaveSchedulePort saveSchedulePort;
    private final RunSubscriptionExecutionPort runSubscriptionExecutionPort;

    @Override
    public void runAll() {
        LocalDateTime now = LocalDateTime.now();
        List<Schedule> dueSchedules = loadDueSchedulesPort.loadDueSchedules(now);
        if (dueSchedules.isEmpty()) {
            log.debug("[SubscriptionMonitorService] 실행할 구독 없음");
            return;
        }
        log.info("[SubscriptionMonitorService] 구독 실행 시작 - {}건", dueSchedules.size());

        // VT per subscription: I/O 대기(Gemini API) 중 carrier thread를 반납해 병렬도를 높인다.
        // try-with-resources: executor.close()가 모든 VT 완료를 기다린 뒤 반환한다.
        // 동시 Gemini 호출 수 제한은 어댑터 Semaphore가 담당한다.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            dueSchedules.forEach(schedule ->
                    executor.submit(() -> executeSchedule(schedule, now)));
        }
    }

    private void executeSchedule(Schedule schedule, LocalDateTime now) {
        Optional<SubscriptionMonitoringConfig> config =
                loadSubscriptionMonitoringConfigPort.loadBySubscriptionId(schedule.subscription().id());
        SubscriptionContext context = buildContext(schedule, config, now);
        try {
            runSubscriptionExecutionPort.execute(context);
            advanceSchedule(schedule, now);
        } catch (RuntimeException e) {
            log.warn(
                    "[SubscriptionMonitorService] 구독 실행 실패 - 스케줄 갱신 스킵. scheduleId={}, subscriptionId={}",
                    schedule.id(),
                    schedule.subscription().id(),
                    e
            );
        }
    }

    private SubscriptionContext buildContext(
            Schedule schedule,
            Optional<SubscriptionMonitoringConfig> monitoringConfig,
            LocalDateTime now
    ) {
        Map<String, Object> params = monitoringConfig
                .map(config -> parameters(config, now))
                .orElseGet(() -> fallbackParameters(schedule.subscription().query()));

        NotificationChannel channel = notificationChannel(schedule.subscription().id());
        String target = channel != null
                ? loadNotificationEndpointPort
                        .loadEnabledByUserIdAndChannel(schedule.subscription().user().id(), channel)
                        .map(NotificationEndpoint::targetAddress)
                        .orElse(null)
                : null;

        return new SubscriptionContext(
                schedule.subscription().id(),
                schedule.subscription().domain().name(),
                schedule.subscription().query(),
                params,
                channel != null ? channel.name() : null,
                target
        );
    }

    private void advanceSchedule(Schedule schedule, LocalDateTime now) {
        try {
            saveSchedulePort.save(new Schedule(
                    schedule.id(),
                    schedule.subscription(),
                    schedule.cronExpr(),
                    now,
                    CronScheduleCalculator.nextRun(schedule.cronExpr(), now)
            ));
        } catch (RuntimeException e) {
            log.warn("Failed to advance schedule. scheduleId={}", schedule.id(), e);
        }
    }

    private Map<String, Object> parameters(SubscriptionMonitoringConfig config, LocalDateTime now) {
        if (isBlank(config.parametersJson())) {
            return configuredToolParameters(config.toolName());
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(config.parametersJson(), PARAMETER_MAP);
            Map<String, Object> normalized = normalizeExecutionParameters(parsed, now);
            putConfiguredToolName(normalized, config.toolName());
            return normalized;
        } catch (JsonProcessingException e) {
            log.warn("parametersJson 파싱 실패 - subscriptionId: {}", config.subscriptionId(), e);
            return Map.of();
        }
    }

    private Map<String, Object> normalizeExecutionParameters(Map<String, Object> params, LocalDateTime now) {
        Map<String, Object> normalized = new LinkedHashMap<>(params);
        // Spring AI 실행 경로는 SearchHousePriceMcpInput을 거치지 않아 정책을 실제 tool 인자로 확정한다.
        if ("LATEST_AVAILABLE_MONTH".equals(String.valueOf(normalized.get("dealYmdPolicy")))
                && !normalized.containsKey("deal_ymd")
                && !normalized.containsKey("dealYmd")) {
            normalized.put("deal_ymd", now.minusMonths(1).format(DEAL_YMD_FORMATTER));
        }
        return normalized;
    }

    private Map<String, Object> configuredToolParameters(String toolName) {
        Map<String, Object> params = new LinkedHashMap<>();
        putConfiguredToolName(params, toolName);
        return params;
    }

    private void putConfiguredToolName(Map<String, Object> params, String toolName) {
        if (!isBlank(toolName)) {
            params.put("dataToolName", toolName);
        }
    }

    private Map<String, Object> fallbackParameters(String query) {
        String region = extractRegion(query);
        return isBlank(region) ? Map.of() : Map.of("region", region);
    }

    private String extractRegion(String query) {
        Matcher matcher = REGION.matcher(query == null ? "" : query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private NotificationChannel notificationChannel(String subscriptionId) {
        return loadEnabledNotificationPreferencePort.loadEnabledBySubscriptionId(subscriptionId).stream()
                .filter(NotificationPreference::enabled)
                .findFirst()
                .map(NotificationPreference::channel)
                .orElse(null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
