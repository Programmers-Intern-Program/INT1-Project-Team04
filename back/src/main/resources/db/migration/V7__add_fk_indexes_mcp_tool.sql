-- mcp_tool 의 FK 컬럼(server_id, domain_id) 인덱스 추가.
-- V2에서 테이블을 만들 때 UNIQUE(server_id, name) 만 걸려 있어 server_id 단독·domain_id 조인이 풀스캔이었다.
-- mcp_tool 은 Flyway 가 생성·관리하는 테이블이므로 인덱스도 마이그레이션으로 둔다.
-- (그 외 ddl-auto 가 생성하는 테이블의 FK 인덱스는 각 JpaEntity 의 @Table(indexes=...) 로 관리한다.)
-- IF NOT EXISTS: 테스트 환경(ddl-auto: create-drop) 및 재실행 호환.
CREATE INDEX IF NOT EXISTS idx_mcp_tool_server_id ON mcp_tool (server_id);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_domain_id ON mcp_tool (domain_id);
