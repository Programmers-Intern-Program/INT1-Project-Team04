-- tool_name: Spring AI가 MCP tool을 직접 선택하므로 저장 불필요 → nullable로 변경
ALTER TABLE subscription_monitoring_config
    ALTER COLUMN tool_name DROP NOT NULL;
