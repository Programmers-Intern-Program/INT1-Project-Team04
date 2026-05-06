package com.back.domain.adapter.out.ai;

import com.back.domain.application.port.out.RunAiMonitorPort;
import com.back.domain.application.port.out.RunSubscriptionExecutionPort;
import com.back.domain.application.service.SubscriptionContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpringAiMonitorAdapter implements RunAiMonitorPort, RunSubscriptionExecutionPort {

    @Nullable
    private final ChatClient monitorChatClient;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final Bucket bucket;

    // [레버 1] system prompt로 tool 호출 순서/조건 유도
    // [레버 2] MCP tool description에 순서/조건 명시 → Python 담당자 담당
    // 두 레버가 일치할수록 AI의 tool 선택이 안정적으로 동작함
    private static final String SYSTEM_PROMPT = """
            당신은 구독 모니터링 에이전트입니다.
            반드시 다음 순서로 tool을 사용하세요:
            1. 사전 check tool → 시장 이벤트/뉴스 확인
            2. 판단: 유의미한 변화가 있을 때만 fetch tool 호출
            3. 변화가 있으면 브리핑 생성 후 Discord 발송
            """;
    // TODO: tool 이름/단계별 조건은 Python 담당자와 합의 후 위 프롬프트에 반영

    public SpringAiMonitorAdapter(
            @Autowired(required = false) ChatClient monitorChatClient,
            ObjectMapper objectMapper,
            @Value("${app.monitor.batch-size:5}") int batchSize,
            @Value("${app.monitor.rpm-limit:10}") int rpmLimit
    ) {
        this.monitorChatClient = monitorChatClient;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.bucket = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rpmLimit)
                        .refillGreedy(rpmLimit, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    @Override
    public void run() {
        if (monitorChatClient == null) {
            log.warn("[SpringAiMonitorAdapter] ChatClient 미구성 — 스킵");
            return;
        }
        log.info("[SpringAiMonitorAdapter] AI 모니터링 트리거 전송");
        monitorChatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("구독 모니터링을 실행해주세요.")
                .call()
                .content();
    }

    // 구독 실행 흐름: 스케줄러가 due 구독 목록을 넘기면 MCP server에 위임
    @Override
    public void execute(List<SubscriptionContext> subscriptions) {
        if (monitorChatClient == null) {
            log.warn("[SpringAiMonitorAdapter] ChatClient 미구성 — 구독 실행 스킵 ({}건)", subscriptions.size());
            return;
        }
        if (subscriptions.isEmpty()) {
            return;
        }
        int total = subscriptions.size();
        int totalBatches = (total + batchSize - 1) / batchSize;
        log.info("[SpringAiMonitorAdapter] 구독 실행 요청 - {}건 / {}개 배치 (배치 크기: {})", total, totalBatches, batchSize);
        for (int i = 0; i < total; i += batchSize) {
            List<SubscriptionContext> batch = subscriptions.subList(i, Math.min(i + batchSize, total));
            int batchIndex = i / batchSize + 1;
            Thread.ofVirtual().start(() -> executeBatch(batch, batchIndex, totalBatches));
        }
    }

    private void executeBatch(List<SubscriptionContext> batch, int batchIndex, int totalBatches) {
        try {
            bucket.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SpringAiMonitorAdapter] 배치 {}/{} 인터럽트 — 스킵", batchIndex, totalBatches);
            return;
        }
        try {
            log.info("[SpringAiMonitorAdapter] 배치 {}/{} 실행 - {}건", batchIndex, totalBatches, batch.size());
            String payload = objectMapper.writeValueAsString(batch);
            monitorChatClient.prompt()
                    .system(PromptTemplate.SUBSCRIPTION_EXECUTION_SYSTEM_PROMPT)
                    .user(payload)
                    .call()
                    .content();
            log.info("[SpringAiMonitorAdapter] 배치 {}/{} 완료", batchIndex, totalBatches);
        } catch (JsonProcessingException e) {
            log.error("[SpringAiMonitorAdapter] 배치 {}/{} 직렬화 실패", batchIndex, totalBatches, e);
        }
    }
}
