package com.back.domain.application.service;

import com.back.domain.application.port.in.PromoteBaselineUseCase;
import com.back.domain.application.port.out.ConsumeBaselinePromoteTokenPort;
import com.back.domain.application.port.out.PromoteSubscriptionBaselinePort;
import com.back.domain.application.result.PromoteBaselineMcpResult;
import com.back.domain.application.result.PromoteBaselineResult;
import com.back.domain.application.result.PromoteTokenConsumption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BaselinePromoteService implements PromoteBaselineUseCase {

    private final ConsumeBaselinePromoteTokenPort consumeTokenPort;
    private final PromoteSubscriptionBaselinePort promoteSubscriptionBaselinePort;

    @Override
    public PromoteBaselineResult promote(String token, boolean dryRun) {
        // 토큰 검증·소비는 짧은 트랜잭션으로 어댑터 안에서 처리한다.
        // MCP 호출은 트랜잭션 밖에서 (CLAUDE.md "@Transactional 범위 최소화").
        PromoteTokenConsumption consumption = consumeTokenPort.consumeOrPeek(token, dryRun);
        return switch (consumption.status()) {
            case NOT_FOUND -> PromoteBaselineResult.notFound();
            case EXPIRED -> PromoteBaselineResult.expired();
            case ALREADY_USED -> PromoteBaselineResult.alreadyUsed();
            case DRY_RUN -> PromoteBaselineResult.dryRun(consumption.subscriptionId());
            case USABLE -> performPromote(consumption);
        };
    }

    private PromoteBaselineResult performPromote(PromoteTokenConsumption consumption) {
        PromoteBaselineMcpResult mcpResult = promoteSubscriptionBaselinePort.promote(
                consumption.subscriptionId(),
                consumption.paramsHash()
        );
        if (mcpResult.promoted()) {
            log.info("Baseline promoted. subscriptionId={}, rowsUpdated={}",
                    consumption.subscriptionId(), mcpResult.rowsUpdated());
            return PromoteBaselineResult.success(consumption.subscriptionId(), mcpResult.rowsUpdated());
        }
        log.warn("Baseline promote skipped or failed. subscriptionId={}, reason={}",
                consumption.subscriptionId(), mcpResult.skippedReason());
        return PromoteBaselineResult.mcpFailed(consumption.subscriptionId(), mcpResult.skippedReason());
    }
}
