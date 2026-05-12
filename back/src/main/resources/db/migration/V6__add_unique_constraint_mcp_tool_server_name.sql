-- mcp_tool(server_id, name) 복합 유니크 제약 보장.
-- V2의 CREATE TABLE IF NOT EXISTS로 인해 테이블이 이미 존재했던 환경에서는
-- 제약이 추가되지 않아 V4의 ON CONFLICT (server_id, name)가 실패한다.
-- 이미 제약이 있는 환경(로컬 fresh DB)에서는 건너뜀.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'mcp_tool_server_id_name_key'
          AND conrelid = 'mcp_tool'::regclass
    ) THEN
        ALTER TABLE mcp_tool ADD CONSTRAINT mcp_tool_server_id_name_key UNIQUE (server_id, name);
    END IF;
END $$;