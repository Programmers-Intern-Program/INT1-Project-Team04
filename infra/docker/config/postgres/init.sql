-- langfuse 전용 DB 생성 (appdb는 POSTGRES_DB 환경변수로 자동 생성됨)
CREATE DATABASE langfuse;
CREATE DATABASE mcp_dev;

-- appdb에 pgvector 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- langfuse DB에도 pgvector 활성화
\c langfuse
CREATE EXTENSION IF NOT EXISTS vector;

-- MCP 내부 DB에도 pgvector 활성화
\c mcp_dev
CREATE EXTENSION IF NOT EXISTS vector;
