-- baseline 갱신 1회용 토큰.
-- Discord/Telegram/Email 알림 메시지에 포함되는 GET 링크 (`/baseline-promote/{token}`)
-- 클릭 시 토큰을 검증·소비하고, MCP `promote_subscription_baseline` Tool 을 호출해
-- 해당 구독의 baseline_summary 를 latest_summary 로 승격시킨다.
--
-- 채널 무관: notification_connection_token 의 channel 컬럼 의미와 충돌하므로 별도 테이블.
-- params_hash 는 MCP DB subscription_snapshot_state 의 행 식별자. nullable — 미지정 시 모든 행 갱신.
-- IF EXISTS / IF NOT EXISTS: 테스트 환경(ddl-auto: create-drop) 호환.
CREATE TABLE IF NOT EXISTS baseline_promote_token (
    token            VARCHAR(64)  PRIMARY KEY,
    subscription_id  VARCHAR(64)  NOT NULL,
    params_hash      VARCHAR(64),
    user_id          BIGINT       NOT NULL,
    notification_id  VARCHAR(36),
    issued_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at       TIMESTAMP    NOT NULL,
    used_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_baseline_promote_token_subscription
    ON baseline_promote_token (subscription_id);

CREATE INDEX IF NOT EXISTS idx_baseline_promote_token_expires
    ON baseline_promote_token (expires_at);
