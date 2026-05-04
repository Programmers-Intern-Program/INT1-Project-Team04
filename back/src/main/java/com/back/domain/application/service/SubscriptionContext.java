package com.back.domain.application.service;

import java.util.Map;

/**
 * Spring AI → MCP server로 전달하는 구독 실행 단위.
 * Schedule + SubscriptionMonitoringConfig + NotificationEndpoint를 flat하게 조합한 AI용 projection.
 */
public record SubscriptionContext(
        String subscriptionId,
        String domain,               // e.g. "real-estate"
        String query,                // e.g. "강남구 아파트 매매 실거래가"
        Map<String, Object> params,  // monitoringConfig.parametersJson 파싱 결과
        String notificationChannel,  // e.g. "DISCORD_DM"
        String notificationTarget    // NotificationEndpoint.targetAddress
) {
}
