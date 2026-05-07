package com.back.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.application.port.out.SaveSchedulePort;
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.schedule.Schedule;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.domain.model.user.User;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: 구독 모니터링 실행 위임 서비스")
class SubscriptionMonitorServiceTest {

    @Test
    @DisplayName("Spring AI 실행 context는 최신월 부동산 정책을 deal_ymd로 보정한다")
    void realEstateContextAddsLatestAvailableDealYmd() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Subscription subscription = subscription("sub-1", user, domain);
        Schedule dueSchedule = new Schedule(
                "schedule-1",
                subscription,
                "0 0 9 * * *",
                null,
                LocalDateTime.now().minusMinutes(1)
        );
        CapturingSubscriptionExecutionPort executionPort = new CapturingSubscriptionExecutionPort();
        CapturingSaveSchedulePort saveSchedulePort = new CapturingSaveSchedulePort();
        SubscriptionMonitorService service = new SubscriptionMonitorService(
                now -> List.of(dueSchedule),
                subscriptionId -> Optional.of(new SubscriptionMonitoringConfig(
                        subscriptionId,
                        null,
                        "apartment_trade_price",
                        "{\"region\":\"강남구\",\"dealYmdPolicy\":\"LATEST_AVAILABLE_MONTH\"}"
                )),
                subscriptionId -> List.of(),
                (userId, channel) -> Optional.empty(),
                saveSchedulePort,
                executionPort
        );

        service.runAll();

        assertThat(executionPort.contexts).hasSize(1);
        assertThat(executionPort.contexts.get(0).params())
                .containsEntry("region", "강남구")
                .containsEntry("deal_ymd", latestAvailableDealYmd());
        assertThat(saveSchedulePort.savedSchedule).isNotNull();
    }

    @Test
    @DisplayName("실행 실패 구독은 스케줄을 갱신하지 않고 성공 구독만 갱신한다")
    void advancesOnlySuccessfullyExecutedSubscriptionSchedules() {
        User user = new User(1L, "user@example.com", "사용자", LocalDateTime.now(), null);
        Domain domain = new Domain(10L, "real-estate");
        Schedule successSchedule = new Schedule(
                "schedule-success",
                subscription("sub-success", user, domain),
                "0 0 9 * * *",
                null,
                LocalDateTime.now().minusMinutes(1)
        );
        Schedule failedSchedule = new Schedule(
                "schedule-failed",
                subscription("sub-failed", user, domain),
                "0 0 9 * * *",
                null,
                LocalDateTime.now().minusMinutes(1)
        );
        CapturingSubscriptionExecutionPort executionPort =
                new CapturingSubscriptionExecutionPort("sub-failed");
        CapturingSaveSchedulePort saveSchedulePort = new CapturingSaveSchedulePort();
        SubscriptionMonitorService service = new SubscriptionMonitorService(
                now -> List.of(successSchedule, failedSchedule),
                subscriptionId -> Optional.empty(),
                subscriptionId -> List.of(),
                (userId, channel) -> Optional.empty(),
                saveSchedulePort,
                executionPort
        );

        service.runAll();

        assertThat(saveSchedulePort.savedSchedules)
                .extracting(schedule -> schedule.subscription().id())
                .containsExactly("sub-success");
    }

    private static String latestAvailableDealYmd() {
        return LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private static Subscription subscription(String id, User user, Domain domain) {
        return new Subscription(
                id,
                user,
                domain,
                "강남구 아파트 매매",
                "apartment_trade_price",
                true,
                LocalDateTime.now()
        );
    }

    private static class CapturingSubscriptionExecutionPort implements RunSubscriptionExecutionPort {
        private final List<SubscriptionContext> contexts = new ArrayList<>();
        private final List<String> failedSubscriptionIds;

        CapturingSubscriptionExecutionPort(String... failedSubscriptionIds) {
            this.failedSubscriptionIds = List.of(failedSubscriptionIds);
        }

        @Override
        public void execute(List<SubscriptionContext> subscriptions) {
            contexts.clear();
            contexts.addAll(subscriptions);
            boolean hasFailedSubscription = subscriptions.stream()
                    .map(SubscriptionContext::subscriptionId)
                    .anyMatch(failedSubscriptionIds::contains);
            if (hasFailedSubscription) {
                throw new RuntimeException("execution failed");
            }
        }
    }

    private static class CapturingSaveSchedulePort implements SaveSchedulePort {
        private final List<Schedule> savedSchedules = new ArrayList<>();
        private Schedule savedSchedule;

        @Override
        public Schedule save(Schedule schedule) {
            savedSchedule = schedule;
            savedSchedules.add(schedule);
            return schedule;
        }
    }
}
