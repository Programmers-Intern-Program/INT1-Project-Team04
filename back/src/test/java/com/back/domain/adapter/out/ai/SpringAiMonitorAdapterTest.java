package com.back.domain.adapter.out.ai;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.back.domain.application.service.SubscriptionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class SpringAiMonitorAdapterTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;

    @BeforeEach
    void setUpChatClientMock() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("ok");
    }

    private static List<SubscriptionContext> subs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new SubscriptionContext("sub-" + i, "real-estate", "query", Map.of(), "DISCORD_DM", "target"))
                .toList();
    }

    @Test
    void splitsSubscriptionsIntoBatches() {
        // 5건 / batchSize=2 → ceil(5/2) = 3배치 → content() 3회 호출 기대
        // execute()는 virtual thread를 fire-and-forget으로 띄우고 즉시 반환하므로
        // Awaitility로 완료 시점까지 폴링한다.
        // rpmLimit=60: bucket이 블로킹되면 타임아웃 안에 배치가 안 끝나 flaky해지므로 넉넉하게 준다.
        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), 2, 60);

        adapter.execute(subs(5));

        await().atMost(Duration.ofSeconds(3))
               .untilAsserted(() -> verify(callSpec, times(3)).content());
    }

    @Test
    void executesBatchesConcurrently() throws InterruptedException {
        // 순차 실행이라면 첫 번째 스레드가 release.await()에서 블로킹되는 동안
        // 두 번째·세 번째 배치가 시작되지 못해 allStarted가 0에 도달하지 못한다.
        // 병렬 실행이면 3개 virtual thread가 모두 content()에 진입해 카운트다운을 마친다.
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        when(callSpec.content()).thenAnswer(inv -> {
            allStarted.countDown();
            release.await();
            return "ok";
        });

        SpringAiMonitorAdapter adapter = new SpringAiMonitorAdapter(chatClient, new ObjectMapper(), 2, 60);
        adapter.execute(subs(5)); // 3배치

        assertTrue(allStarted.await(3, java.util.concurrent.TimeUnit.SECONDS), "3개 배치가 동시에 실행되지 않았다");
        release.countDown(); // 대기 중인 virtual thread 해제
    }
}
