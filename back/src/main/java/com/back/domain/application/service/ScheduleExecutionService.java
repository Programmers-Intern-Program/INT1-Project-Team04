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
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * [Domain Service] 예약된 스케줄에 따라 AI 분석 및 알림을 총괄하는 Service
 * * 비즈니스 로직의 오케스트레이터 역할
 */
@Service
@RequiredArgsConstructor
public class ScheduleExecutionService implements RunDueSchedulesUseCase {

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
                executionQuery(schedule.subscription().query(), monitoringConfig)
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

    private String executionQuery(String query, Optional<SubscriptionMonitoringConfig> monitoringConfig) {
        return monitoringConfig
                .map(config -> query
                        + "\n\n[monitoring]\n"
                        + "intent: " + config.intent() + "\n"
                        + "parameters: " + config.parametersJson())
                .orElse(query);
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
