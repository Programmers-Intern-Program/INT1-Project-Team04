-- tool_name: Spring AI가 MCP tool을 직접 선택하므로 저장 불필요 → nullable로 변경
-- IF EXISTS: 테스트 환경(ddl-auto: create-drop)에서는 Flyway 실행 시점에 테이블이 없으므로 조건부 처리
ALTER TABLE IF EXISTS subscription_monitoring_config
    ALTER COLUMN tool_name DROP NOT NULL;
