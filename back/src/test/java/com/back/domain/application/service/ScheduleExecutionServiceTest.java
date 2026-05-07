package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.port.out.LoadDueSchedulesPort;
import com.back.domain.application.port.out.LoadEnabledNotificationPreferencePort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.port.out.LoadRecentAiDataHubPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.application.port.out.SaveNotificationDeliveryPort;
import com.back.domain.application.port.out.SaveNotificationPort;
import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.SendNotificationPort;
import com.back.domain.application.service.monitoring.MonitoringAlertMessageBuilder;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringBriefingResult;
import com.back.domain.application.service.monitoring.MonitoringChangeDetector;
import com.back.domain.application.service.monitoring.MonitoringQueryMatcher;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.hub.AiDataHub;
import com.back.domain.model.mcp.McpServer;
import com.back.domain.model.mcp.McpTool;
import com.back.domain.model.notification.Notification;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.notification.NotificationDeliveryStatus;
import com.back.domain.model.notification.NotificationEndpoint;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 스케줄 실행 비즈니스 로직 테스트")
class ScheduleExecutionServiceTest {

    @Test
    @DisplayName("Application: 예정된 스케줄의 첫 실행은 기준 스냅샷을 저장하고 다음 스케줄만 갱신한다")
    void firstRunSavesBaselineHubAndScheduleWithoutNotification() {
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
                new FakeLoadRecentAiDataHubPort(),
                saveNotificationPort,
                sendNotificationPort,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isEqualTo("search_house_price");
        assertThat(executeMcpToolPort.executedInput())
                .containsEntry("region", "강남구")
                .containsEntry("deal_ymd", latestAvailableDealYmd());
        assertThat(saveAiDataHubPort.saved).hasSize(1);
        assertThat(saveAiDataHubPort.saved.get(0).metadata())
                .contains("\"execution\"")
                .contains("\"subscription_id\":\"sub-1\"")
                .contains("\"schedule_id\":\"schedule-1\"");
        assertThat(saveNotificationPort.saved).isEmpty();
        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).lastRun()).isNotNull();
        assertThat(saveSchedulePort.saved.get(0).nextRun()).isAfter(saveSchedulePort.saved.get(0).lastRun());
    }

    @Test
    @DisplayName("Application: summary 없는 MCP 결과는 변화 감지를 생략하고 기존 알림 흐름으로 처리한다")
    void sendsNotificationForMcpResultWithoutStructuredSummary() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(20L, "law");
        Subscription subscription = new Subscription("sub-law", user, domain, "근로기준법 변경 확인", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-law", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                200L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_law_info",
                "법령 조회",
                "{}"
        );
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort(
                "law result content",
                rawPassthroughMetadata()
        );
        FakeSaveAiDataHubPort saveAiDataHubPort = new FakeSaveAiDataHubPort();
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        FakeGenerateMonitoringBriefingPort generateBriefingPort = new FakeGenerateMonitoringBriefingPort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                executeMcpToolPort,
                saveAiDataHubPort,
                new FakeLoadRecentAiDataHubPort(),
                saveNotificationPort,
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                generateBriefingPort,
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(saveAiDataHubPort.saved).hasSize(1);
        assertThat(saveAiDataHubPort.saved.get(0).metadata())
                .contains("\"subscription_id\":\"sub-law\"")
                .contains("\"schedule_id\":\"schedule-law\"");
        assertThat(saveNotificationPort.saved).extracting(Notification::status)
                .contains(NotificationStatus.PENDING, NotificationStatus.SENT);
        assertThat(saveNotificationPort.saved).extracting(Notification::message)
                .containsOnly("law result content");
        assertThat(generateBriefingPort.requests).isEmpty();
        assertThat(saveSchedulePort.saved).hasSize(1);
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
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).nextRun().getMonth()).isEqualTo(Month.JANUARY);
        assertThat(saveSchedulePort.saved.get(0).nextRun().getDayOfMonth()).isEqualTo(1);
        assertThat(saveSchedulePort.saved.get(0).nextRun().getHour()).isZero();
    }

    @Test
    @DisplayName("Application: 도메인에 연결된 MCP 도구가 없으면 해당 스케줄만 건너뛰고 다음 실행 시각을 갱신한다")
    void skipsScheduleWhenMcpToolDoesNotExistAndAdvancesSchedule() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).id()).isEqualTo("schedule-1");
        assertThat(saveSchedulePort.saved.get(0).lastRun()).isNotNull();
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
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                loadConfigPort,
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(previousHub(user, tool, subscription.id(), 100000)),
                saveNotificationPort,
                notification -> false,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(saveNotificationPort.saved).extracting(Notification::status)
                .contains(NotificationStatus.PENDING, NotificationStatus.FAILED);
        assertThat(saveNotificationPort.saved.get(1).sentAt()).isNull();
        assertThat(saveNotificationPort.saved.get(1).channel()).isEqualTo("DISCORD");
    }

    @Test
    @DisplayName("Application: MCP 실행이 실패하면 해당 스케줄만 건너뛰고 다음 실행 시각을 갱신한다")
    void isolatesFailedScheduleAndAdvancesIt() {
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
                (mcpTool, arguments) -> {
                    throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
                },
                saveAiDataHubPort,
                new FakeLoadRecentAiDataHubPort(),
                saveNotificationPort,
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(saveAiDataHubPort.saved).isEmpty();
        assertThat(saveNotificationPort.saved).isEmpty();
        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).id()).isEqualTo("schedule-1");
        assertThat(saveSchedulePort.saved.get(0).lastRun()).isNotNull();
    }

    @Test
    @DisplayName("Application: 한 스케줄이 실패해도 다음 예정 스케줄은 계속 실행한다")
    void continuesExecutingRemainingSchedulesWhenOneFails() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription firstSubscription = new Subscription("sub-fail", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Subscription secondSubscription = new Subscription("sub-ok", user, domain, "서초구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule firstSchedule = new Schedule("schedule-fail", firstSubscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        Schedule secondSchedule = new Schedule("schedule-ok", secondSubscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        int[] calls = {0};
        FakeSaveAiDataHubPort saveAiDataHubPort = new FakeSaveAiDataHubPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(firstSchedule, secondSchedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        subscriptionId.equals("sub-fail")
                                ? "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                                : "{\"region\":\"서초구\",\"condition\":\"5% 이상 상승\"}"
                )),
                subscriptionId -> List.of(),
                (mcpTool, arguments) -> {
                    calls[0]++;
                    if (calls[0] == 1) {
                        throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
                    }
                    return new McpExecutionResult("REAL_ESTATE", "result content", snapshotMetadata(null, 106000));
                },
                saveAiDataHubPort,
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(calls[0]).isEqualTo(2);
        assertThat(saveAiDataHubPort.saved).hasSize(1);
        assertThat(saveAiDataHubPort.saved.get(0).metadata()).contains("\"subscription_id\":\"sub-ok\"");
        assertThat(saveSchedulePort.saved).extracting(Schedule::id)
                .containsExactly("schedule-fail", "schedule-ok");
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
                new FakeLoadRecentAiDataHubPort(previousHub(user, configuredTool, subscription.id(), 100000)),
                saveNotificationPort,
                notification -> true,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isEqualTo("search_house_price_v2");
        assertThat(executeMcpToolPort.executedInput())
                .containsEntry("region", "강남구")
                .containsEntry("deal_ymd", latestAvailableDealYmd())
                .doesNotContainKey("condition");
        assertThat(saveNotificationPort.saved).extracting(Notification::channel)
                .containsOnly("TELEGRAM_DM");
    }

    @Test
    @DisplayName("Application: 설정된 MCP 도구가 없으면 도메인 기본 도구로 대체 실행하지 않는다")
    void doesNotFallbackToDefaultDomainToolWhenConfiguredToolIsMissing() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 오피스텔 전월세", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool defaultTool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "아파트 매매 실거래가 조회",
                "{}"
        );
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        FakeSaveAiDataHubPort saveAiDataHubPort = new FakeSaveAiDataHubPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(defaultTool, null),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_offi_rent",
                        "officetel_rent_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )),
                subscriptionId -> List.of(),
                executeMcpToolPort,
                saveAiDataHubPort,
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isNull();
        assertThat(saveAiDataHubPort.saved).isEmpty();
        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).id()).isEqualTo("schedule-1");
    }

    @Test
    @DisplayName("Application: 부동산 legacy 설정은 도메인 첫 도구가 아니라 아파트 매매 도구로 실행한다")
    void legacyRealEstateScheduleUsesExplicitApartmentTradeTool() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 아파트 매매", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool arbitraryDomainTool = new McpTool(
                101L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_offi_rent",
                "오피스텔 전월세 실거래가 조회",
                "{}"
        );
        McpTool apartmentTradeTool = new McpTool(
                100L,
                arbitraryDomainTool.server(),
                domain,
                "search_house_price",
                "아파트 매매 실거래가 조회",
                "{}"
        );
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(arbitraryDomainTool, apartmentTradeTool),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                executeMcpToolPort,
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isEqualTo("search_house_price");
    }

    @Test
    @DisplayName("Application: 아파트 매매 외 부동산 도구도 region과 deal_ymd 입력으로 실행한다")
    void runsOtherRealEstateToolsWithMolitRealEstateInput() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-1", user, domain, "강남구 오피스텔 전월세", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool defaultTool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "아파트 매매 실거래가 조회",
                "{}"
        );
        McpTool configuredTool = new McpTool(
                101L,
                defaultTool.server(),
                domain,
                "search_offi_rent",
                "오피스텔 전월세 실거래가 조회",
                "{}"
        );
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(defaultTool, configuredTool),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_offi_rent",
                        "officetel_rent_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}"
                )),
                subscriptionId -> List.of(),
                executeMcpToolPort,
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isEqualTo("search_offi_rent");
        assertThat(executeMcpToolPort.executedInput())
                .containsEntry("region", "강남구")
                .containsEntry("deal_ymd", latestAvailableDealYmd())
                .doesNotContainKey("condition")
                .doesNotContainKey("dealYmdPolicy");
    }

    @Test
    @DisplayName("Application: 변화가 감지되면 AI 브리핑 알림을 우선 발송한다")
    void sendsAiBriefingNotificationWhenChangeDetected() {
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
        FakeGenerateMonitoringBriefingPort generateBriefingPort =
                new FakeGenerateMonitoringBriefingPort("AI 브리핑 알림");
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                loadConfigPort,
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(previousHub(user, tool, subscription.id(), 100000)),
                saveNotificationPort,
                notification -> true,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                generateBriefingPort,
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(generateBriefingPort.requests).hasSize(1);
        assertThat(generateBriefingPort.requests.get(0).decision().metricKey()).isEqualTo("avg_deal_amount");
        assertThat(saveNotificationPort.saved).extracting(Notification::message)
                .containsOnly("AI 브리핑 알림");
    }

    @Test
    @DisplayName("Application: AI가 알림을 비추천하면 정량 변화가 감지되어도 알림을 보내지 않는다")
    void skipsNotificationWhenAiDoesNotRecommendNotification() {
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
        FakeGenerateMonitoringBriefingPort generateBriefingPort =
                new FakeGenerateMonitoringBriefingPort(new MonitoringBriefingResult(false, "표본 부족으로 알림 비추천"));
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                loadConfigPort,
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(previousHub(user, tool, subscription.id(), 100000)),
                saveNotificationPort,
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                generateBriefingPort,
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(generateBriefingPort.requests).hasSize(1);
        assertThat(saveNotificationPort.saved).isEmpty();
        assertThat(saveSchedulePort.saved).hasSize(1);
    }

    @Test
    @DisplayName("Application: 정량 임계값 미달이면 AI가 알림을 추천해도 알림을 보내지 않는다")
    void skipsNotificationWhenBackendThresholdIsNotReachedEvenIfAiRecommendsNotification() {
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
        FakeGenerateMonitoringBriefingPort generateBriefingPort =
                new FakeGenerateMonitoringBriefingPort(new MonitoringBriefingResult(true, "AI가 유의미한 변화로 판단했습니다."));
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                loadConfigPort,
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(102000),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(previousHub(user, tool, subscription.id(), 100000)),
                saveNotificationPort,
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                generateBriefingPort,
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(generateBriefingPort.requests).isEmpty();
        assertThat(saveNotificationPort.saved).isEmpty();
        assertThat(saveSchedulePort.saved).hasSize(1);
    }

    @Test
    @DisplayName("Application: 이전 스냅샷은 최근 20개 제한 전에 구독 기준으로 좁혀 조회한다")
    void loadsPreviousSnapshotBySubscriptionBeforeRecentLimit() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = new Subscription("sub-target", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule schedule = new Schedule("schedule-1", subscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        List<AiDataHub> recentHubs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recentHubs.add(previousHub(user, tool, "other-sub-" + i, 100000));
        }
        recentHubs.add(previousHub(user, tool, subscription.id(), 100000));
        FakeGenerateMonitoringBriefingPort generateBriefingPort =
                new FakeGenerateMonitoringBriefingPort("AI 브리핑 알림");
        FakeSaveNotificationPort saveNotificationPort = new FakeSaveNotificationPort();
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                loadConfigPort,
                subscriptionId -> List.of(),
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(recentHubs),
                saveNotificationPort,
                notification -> true,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                generateBriefingPort,
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(generateBriefingPort.requests).hasSize(1);
        assertThat(saveNotificationPort.saved).extracting(Notification::message)
                .containsOnly("AI 브리핑 알림");
    }

    @Test
    @DisplayName("Application: AI 브리핑 변화 알림은 실제 채널 발송용 Delivery를 생성한다")
    void createsNotificationDeliveryForAiBriefingChangeAlert() {
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
        FakeSaveNotificationDeliveryPort saveDeliveryPort = new FakeSaveNotificationDeliveryPort();
        LoadEnabledNotificationPreferencePort loadPreferencePort = subscriptionId -> List.of(
                new NotificationPreference("pref-1", subscriptionId, NotificationChannel.TELEGRAM_DM, true)
        );
        NotificationDeliveryCreationService deliveryCreationService = new NotificationDeliveryCreationService(
                loadPreferencePort,
                (userId, channel) -> Optional.of(new NotificationEndpoint(
                        "endpoint-1",
                        userId,
                        channel,
                        "123456789",
                        true
                )),
                saveDeliveryPort
        );
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )),
                loadPreferencePort,
                new FakeExecuteMcpToolPort(),
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(previousHub(user, tool, subscription.id(), 100000)),
                new FakeSaveNotificationPort(),
                notification -> true,
                new FakeSaveSchedulePort(),
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort("AI 브리핑 알림"),
                deliveryCreationService
        );

        service.runDueSchedules();

        assertThat(saveDeliveryPort.saved).hasSize(1);
        NotificationDelivery delivery = saveDeliveryPort.saved.get(0);
        assertThat(delivery.channel()).isEqualTo(NotificationChannel.TELEGRAM_DM);
        assertThat(delivery.recipient()).isEqualTo("123456789");
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.message()).contains("AI 브리핑 알림", "요청: 강남구 아파트 실거래가");
    }

    @Test
    @DisplayName("Application: MCP input validation rejects malformed configured deal_ymd before calling MCP")
    void rejectsMalformedConfiguredDealYmdBeforeCallingMcpAndAdvancesSchedule() {
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
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        LoadSubscriptionMonitoringConfigPort loadConfigPort = subscriptionId -> Optional.of(
                new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"deal_ymd\":\"2024-03\"}"
                )
        );
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(schedule),
                new FakeLoadMcpToolPort(tool),
                loadConfigPort,
                subscriptionId -> List.of(),
                executeMcpToolPort,
                new FakeSaveAiDataHubPort(),
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executedToolName).isNull();
        assertThat(saveSchedulePort.saved).hasSize(1);
        assertThat(saveSchedulePort.saved.get(0).id()).isEqualTo("schedule-1");
    }

    @Test
    @DisplayName("Application: 같은 실행 배치의 동일 MCP 요청은 한 번만 호출하고 결과를 재사용한다")
    void reusesSameMcpResultWithinOneDueScheduleBatch() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription firstSubscription = new Subscription("sub-1", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Subscription secondSubscription = new Subscription("sub-2", user, domain, "강남구 아파트 실거래가", "create", true, LocalDateTime.now());
        Schedule firstSchedule = new Schedule("schedule-1", firstSubscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        Schedule secondSchedule = new Schedule("schedule-2", secondSubscription, "0 0 * * * *", null, LocalDateTime.now().minusMinutes(1));
        McpTool tool = new McpTool(
                100L,
                new McpServer(1L, "default-mcp", "server", "http://localhost:8090/tools/execute"),
                domain,
                "search_house_price",
                "부동산 실거래가 조회",
                "{}"
        );
        FakeExecuteMcpToolPort executeMcpToolPort = new FakeExecuteMcpToolPort();
        FakeSaveAiDataHubPort saveAiDataHubPort = new FakeSaveAiDataHubPort();
        FakeSaveSchedulePort saveSchedulePort = new FakeSaveSchedulePort();
        ScheduleExecutionService service = new ScheduleExecutionService(
                new FakeLoadDueSchedulesPort(firstSchedule, secondSchedule),
                new FakeLoadMcpToolPort(tool),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        "search_house_price",
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"condition\":\"5% 이상 상승\"}"
                )),
                subscriptionId -> List.of(),
                executeMcpToolPort,
                saveAiDataHubPort,
                new FakeLoadRecentAiDataHubPort(),
                new FakeSaveNotificationPort(),
                notification -> true,
                saveSchedulePort,
                new MonitoringQueryMatcher(),
                new MonitoringChangeDetector(),
                new MonitoringAlertMessageBuilder(),
                new FakeGenerateMonitoringBriefingPort(),
                noOpDeliveryCreationService()
        );

        service.runDueSchedules();

        assertThat(executeMcpToolPort.executionCount).isEqualTo(1);
        assertThat(saveAiDataHubPort.saved).hasSize(2);
        assertThat(saveAiDataHubPort.saved).extracting(AiDataHub::metadata)
                .allSatisfy(metadata -> assertThat(metadata).contains("\"execution\""));
        assertThat(saveAiDataHubPort.saved).extracting(AiDataHub::metadata)
                .anySatisfy(metadata -> assertThat(metadata).contains("\"subscription_id\":\"sub-1\""))
                .anySatisfy(metadata -> assertThat(metadata).contains("\"subscription_id\":\"sub-2\""));
        assertThat(saveSchedulePort.saved).hasSize(2);
    }

    private record FakeLoadDueSchedulesPort(List<Schedule> schedules) implements LoadDueSchedulesPort {

        private FakeLoadDueSchedulesPort(Schedule... schedules) {
            this(Arrays.asList(schedules));
        }

        @Override
        public List<Schedule> loadDueSchedules(LocalDateTime now) {
            return schedules;
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
        private Map<String, Object> executedArguments;
        private int executionCount;
        private final String content;
        private final String metadata;
        private final int avgDealAmount;

        private FakeExecuteMcpToolPort() {
            this(106000);
        }

        private FakeExecuteMcpToolPort(int avgDealAmount) {
            this("result content", null, avgDealAmount);
        }

        private FakeExecuteMcpToolPort(String content, String metadata) {
            this(content, metadata, 106000);
        }

        private FakeExecuteMcpToolPort(String content, String metadata, int avgDealAmount) {
            this.content = content;
            this.metadata = metadata;
            this.avgDealAmount = avgDealAmount;
        }

        @Override
        public McpExecutionResult execute(McpTool tool, Map<String, Object> arguments) {
            executionCount++;
            this.executedToolName = tool.name();
            this.executedArguments = arguments;
            return new McpExecutionResult("REAL_ESTATE", content, metadata == null ? snapshotMetadata(null, avgDealAmount) : metadata);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> executedInput() {
            return (Map<String, Object>) executedArguments.get("input");
        }
    }

    private static String latestAvailableDealYmd() {
        return LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private static AiDataHub previousHub(User user, McpTool tool, String subscriptionId, int avgDealAmount) {
        return new AiDataHub(
                "previous-hub",
                user,
                tool,
                "REAL_ESTATE",
                "previous content",
                null,
                snapshotMetadata(subscriptionId, avgDealAmount),
                LocalDateTime.now().minusDays(1)
        );
    }

    private static String snapshotMetadata(String subscriptionId, int avgDealAmount) {
        String execution = subscriptionId == null
                ? ""
                : """
                  ,"execution":{"subscription_id":"%s","schedule_id":"previous-schedule"}
                  """.formatted(subscriptionId);
        return """
                {
                  "structured": {
                    "summary": {"count": 10, "avg_deal_amount": %d},
                    "query": {"region": "강남구", "lawd_cd": "11680", "deal_ymd": "202403"}
                  },
                  "metadata": {"tool_name": "search_house_price"}%s
                }
                """.formatted(avgDealAmount, execution);
    }

    private static String rawPassthroughMetadata() {
        return """
                {
                  "structured": {
                    "raw": "<xml/>",
                    "raw_truncated": false,
                    "raw_length": 6,
                    "query": {"query": "근로기준법"}
                  },
                  "metadata": {"tool_name": "search_law_info"}
                }
                """;
    }

    private static NotificationDeliveryCreationService noOpDeliveryCreationService() {
        return new NotificationDeliveryCreationService(
                subscriptionId -> List.of(),
                (userId, channel) -> Optional.empty(),
                new FakeSaveNotificationDeliveryPort()
        );
    }

    private record FakeLoadRecentAiDataHubPort(List<AiDataHub> hubs) implements LoadRecentAiDataHubPort {

        private FakeLoadRecentAiDataHubPort(AiDataHub... hubs) {
            this(Arrays.asList(hubs));
        }

        @Override
        public List<AiDataHub> loadRecentByUserIdAndToolId(Long userId, Long toolId, int limit) {
            return hubs.stream().limit(limit).toList();
        }

        @Override
        public List<AiDataHub> loadRecentByUserIdAndToolIdAndSubscriptionId(
                Long userId,
                Long toolId,
                String subscriptionId,
                int limit
        ) {
            String marker = "\"subscription_id\":\"%s\"".formatted(subscriptionId);
            return hubs.stream()
                    .filter(hub -> hub.metadata() != null && hub.metadata().contains(marker))
                    .limit(limit)
                    .toList();
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

    private static class FakeGenerateMonitoringBriefingPort implements GenerateMonitoringBriefingPort {

        private final Optional<MonitoringBriefingResult> briefing;
        private final List<MonitoringBriefingRequest> requests = new ArrayList<>();

        private FakeGenerateMonitoringBriefingPort() {
            this((MonitoringBriefingResult) null);
        }

        private FakeGenerateMonitoringBriefingPort(String briefing) {
            this(briefing == null ? null : new MonitoringBriefingResult(true, briefing));
        }

        private FakeGenerateMonitoringBriefingPort(MonitoringBriefingResult briefing) {
            this.briefing = Optional.ofNullable(briefing);
        }

        @Override
        public Optional<MonitoringBriefingResult> generate(MonitoringBriefingRequest request) {
            requests.add(request);
            return briefing;
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

    private static class FakeSaveNotificationDeliveryPort implements SaveNotificationDeliveryPort {

        private final List<NotificationDelivery> saved = new ArrayList<>();

        @Override
        public NotificationDelivery save(NotificationDelivery delivery) {
            saved.add(delivery);
            return delivery;
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
