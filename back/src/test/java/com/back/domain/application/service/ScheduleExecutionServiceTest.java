package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadDueSchedulesPort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.application.port.out.SaveNotificationPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SendNotificationPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.Notification;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationPreference;
import com.back.domain.model.notification.NotificationStatus;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.domain.model.user.User;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 스케줄 실행 비즈니스 로직 테스트")
class ScheduleExecutionServiceTest {

    @Test
    @DisplayName("Application: 예정된 스케줄을 실행하면 [AI 분석 -> 데이터 저장 -> 알림 발송 -> 다음 스케줄 갱신] 프로세스가 통합적으로 수행된다")
    void runsDueScheduleAndSavesHubNotificationAndSchedule() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );

        FakeLoadDueSchedulesPort loadDueSchedulesPort = new FakeLoadDueSchedulesPort(schedule);
        FakeLoadMcpToolPort loadMcpToolPort = new FakeLoadMcpToolPort(tool);
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        FakeSaveAiDataHubPort saveAiDataHubPort = new FakeSaveAiDataHubPort();
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        SendNotificationPort sendNotificationPort = notification -> true;

        ScheduleExecutionService service = new ScheduleExecutionService(
                loadDueSchedulesPort,
                loadMcpToolPort,
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                executeMcpToolPort,
                saveAiDataHubPort,
                saveNotificationPort,
                sendNotificationPort,
                saveSchedulePort
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isEqualTo("search_house_price");
        assertThat(executeMcpToolPort.executedQuery).isEqualTo("강남구 아파트 실거래가");
        assertThat(saveAiDataHubPort.saved).hasSize(1);
        assertThat(saveNotificationPort.saved).extracting(Notification::status)
                .contains(NotificationStatus.PENDING, NotificationStatus.SENT);
        assertThat(saveNotificationPort.saved).extracting(Notification::channel)
                .containsOnly("DISCORD");
        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).lastRun()).isNotNull();
        assertThat(saveSchedulePort.saved.get(0).nextRun()).isAfter(saveSchedulePort.saved.get(0).lastRun());
    }

    @Test
    @DisplayName("Application: 스케줄 실행 후 저장된 cron 표현식 기준으로 다음 실행 시각을 갱신한다")
    void updatesNextRunByCronExpression() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 0 1 1 *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort
        );

        service.runDueSchedules();

        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).nextRun().getMonth()).isEqualTo(Month.JANUARY);
        assertThat(saveSchedulePort.saved.get(0).nextRun().getDayOfMonth()).isEqualTo(1);
        assertThat(saveSchedulePort.saved.get(0).nextRun().getHour()).isZero();
    }

    @Test
    @DisplayName("Application: 도메인에 연결된 MCP 도구가 없으면 예외를 발생시킨다")
    void throwsWhenMcpToolDoesNotExist() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                new FakeSaveSchedulePort()
        );

        assertThatThrownBy(service::runDueSchedules)
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MCP_TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("Application: 알림 전송에 실패하면 FAILED 상태로 저장한다")
    void savesFailedNotificationWhenSendingFails() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                saveNotificationPort,
                notification -> false,
                new FakeSaveSchedulePort()
        );

        service.runDueSchedules();

        assertThat(saveNotificationPort.saved).extracting(Notification::status)
                .contains(NotificationStatus.PENDING, NotificationStatus.FAILED);
        assertThat(saveNotificationPort.saved.get(1).sentAt()).isNull();
        assertThat(saveNotificationPort.saved.get(1).channel()).isEqualTo("DISCORD");
    }

    @Test
    @DisplayName("Application: MCP 실행이 실패하면 후속 저장을 수행하지 않고 예외를 전파한다")
    void stopsWhenMcpExecutionFails() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        FakeSaveAiDataHubPort saveAiDataHubPort = new FakeSaveAiDataHubPort();
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                (mcpTool, query) -> {
                    throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
                },
                saveAiDataHubPort,
                saveNotificationPort,
                notification -> true,
                saveSchedulePort
        );

        assertThatThrownBy(service::runDueSchedules)
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MCP_REQUEST_FAILED);
        assertThat(saveAiDataHubPort.saved).isEmpty();
        assertThat(saveNotificationPort.saved).isEmpty();
        assertThat(saveSchedulePort.saved).isEmpty();
    }

    @Test
    @DisplayName("Application: 저장된 모니터링 설정과 알림 채널 기준으로 MCP 실행 및 알림 저장을 수행한다")
    void runsScheduleWithStoredMonitoringConfigAndNotificationPreference() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool defaultTool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        McpTool configuredTool = new McpTool(
                101L,
                defaultTool.server(),
                domain,
                "search_house_price_v2",
                "부동산 실거래가 조회 v2",
                "{}"
        );
        FakeLoadMcpToolPort loadMcpToolPort = new FakeLoadMcpToolPort(defaultTool, configuredTool);
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price_v2",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )
        );
        LoadEnabledNotificationPreferencePort loadPreferencePort = subscriptionId -> List.of(
                new NotificationPreference("pref-1", subscriptionId, NotificationChannel.TELEGRAM_DM, true)
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                loadMcpToolPort,
                loadConfigPort,
                loadPreferencePort,
                executeMcpToolPort,
                new FakeSaveAiDataHubPort(),
                saveNotificationPort,
                notification -> true,
                new FakeSaveSchedulePort()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isEqualTo("search_house_price_v2");
        assertThat(executeMcpToolPort.executedQuery)
                .contains("강남구 아파트 실거래가")
                .contains("apartment_trade_price")
                .contains("\"condition\":\"5% 이상 상승\"");
        assertThat(saveNotificationPort.saved).extracting(Notification::channel)
                .containsOnly("TELEGRAM_DM");
    }

    private record FakeLoadDueSchedulesPort(Schedule schedule) implements LoadDueSchedulesPort {

        @Override
        public List<Schedule> loadDueSchedules(LocalDateTime now) {
            return List.of(schedule);
        }
    }

    private record FakeLoadMcpToolPort(McpTool tool, McpTool namedTool) implements LoadMcpToolPort {

        private FakeLoadMcpToolPort() {
            this(null, null);
        }

        private FakeLoadMcpToolPort(McpTool tool) {
            this(tool, tool);
        }

        @Override
        public Optional<McpTool> loadByDomainId(Long domainId) {
            return Optional.ofNullable(tool);
        }

        @Override
        public Optional<McpTool> loadByDomainIdAndName(Long domainId, String toolName) {
            return Optional.ofNullable(namedTool)
                    .filter(tool -> tool.name().equals(toolName));
        }
    }

    private static class FakeExecuteMcpToolPort implements ExecuteMcpToolPort {

        private String executedToolName;
        private String executedQuery;

        @Override
        public McpExecutionResult execute(McpTool tool, String query) {
            this.executedToolName = tool.name();
            this.executedQuery = query;
            return new McpExecutionResult("REAL_ESTATE", "result content", "{\"source\":\"mcp\"}");
        }
    }

    private static class FakeSaveAiDataHubPort implements SaveAiDataHubPort {

        private final List<AiDataHub> saved = new ArrayList<>();

        @Override
        public AiDataHub save(AiDataHub aiDataHub) {
            saved.add(aiDataHub);
            return aiDataHub;
        }
    }

    private static class FakeSaveNotificationPort implements SaveNotificationPort {

        private final List<Notification> saved = new ArrayList<>();

        @Override
        public Notification save(Notification notification) {
            saved.add(notification);
            return notification;
        }
    }

    private static class FakeSaveSchedulePort implements SaveSchedulePort {

        private final List<Schedule> saved = new ArrayList<>();

        @Override
        public Schedule save(Schedule schedule) {
            saved.add(schedule);
            return schedule;
        }
    }
}
