package com.back.domain.adapter.in.event;

import com.back.domain.application.event.BaselineInitializationRequested;
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 구독 확정 트랜잭션 커밋 후 baseline 수집을 비동기로 실행한다.
 *
 * HTTP 응답은 즉시 반환되고, 첫 baseline 수집은 가상 스레드에서 백그라운드로 처리된다.
 * 실패해도 cron 기반 정기 실행이 다음 회차에서 재시도하므로 사용자 흐름을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaselineInitializationListener {

    private final RunSubscriptionExecutionPort runSubscriptionExecutionPort;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBaselineInitializationRequested(BaselineInitializationRequested event) {
        String subscriptionId = event.context().subscriptionId();
        log.info("[BaselineInitializationListener] baseline 비동기 수집 시작 - subscriptionId={}", subscriptionId);
        try {
            runSubscriptionExecutionPort.execute(event.context());
            log.info("[BaselineInitializationListener] baseline 수집 완료 - subscriptionId={}", subscriptionId);
        } catch (RuntimeException e) {
            // baseline 수집은 cron이 이후 회차에서 다시 시도하므로 사용자 흐름을 막지 않는다.
            log.warn("[BaselineInitializationListener] baseline 수집 실패 - subscriptionId={}", subscriptionId, e);
        }
    }
}
