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
        Subscription subscription = new Subscription(
                "sub-1",
                user,
                domain,
                "강남구 아파트 매매",
                "apartment_trade_price",
                true,
                LocalDateTime.now()
        );
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

    private static String latestAvailableDealYmd() {
        return LocalDateTime.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private static class CapturingSubscriptionExecutionPort implements RunSubscriptionExecutionPort {
        private final List<SubscriptionContext> contexts = new ArrayList<>();

        @Override
        public void execute(List<SubscriptionContext> subscriptions) {
            contexts.clear();
            contexts.addAll(subscriptions);
        }
    }

    private static class CapturingSaveSchedulePort implements SaveSchedulePort {
        private Schedule savedSchedule;

        @Override
        public Schedule save(Schedule schedule) {
            savedSchedule = schedule;
            return schedule;
        }
    }
}
