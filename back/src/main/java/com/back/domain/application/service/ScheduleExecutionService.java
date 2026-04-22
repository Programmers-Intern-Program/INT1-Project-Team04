package com.back.domain.application.service;

import com.back.domain.application.port.in.RunDueSchedulesUseCase;
import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.LoadDueSchedulesPort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.application.port.out.SaveNotificationPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SendNotificationPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.Notification;
import com.back.domain.model.notification.NotificationStatus;
import com.back.domain.model.schedule.Schedule;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
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
        McpTool tool = loadMcpToolPort.loadByDomainId(schedule.subscription().domain().id())
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_TOOL_NOT_FOUND));

        McpExecutionResult result = executeMcpToolPort.execute(tool, schedule.subscription().query());

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
                "KAKAO",
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
                now.plusHours(1)
        ));
    }
}
