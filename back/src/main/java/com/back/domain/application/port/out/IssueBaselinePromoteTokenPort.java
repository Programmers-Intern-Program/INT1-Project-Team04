package com.back.domain.application.port.out;

import java.time.Duration;

public interface IssueBaselinePromoteTokenPort {

    /**
     * baseline 갱신 1회용 토큰을 발급한다.
     *
     * @return 발급된 토큰 문자열 (URL-safe, secret)
     */
    String issue(
            String subscriptionId,
            String paramsHash,
            Long userId,
            String notificationId,
            Duration ttl
    );
}
