"""MCP 서버 환경 설정.

환경 변수 → pydantic-settings → 전역 settings 객체로 노출.
모든 모듈은 직접 os.environ 을 읽지 말고 이 settings 를 import 한다.
"""

from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

McpTransport = Literal["stdio", "sse"]


class Settings(BaseSettings):
    """애플리케이션 설정."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    pg_url: str = Field(
        description="MCP 내부 DB 접속 URL (asyncpg 드라이버). default 없음 — .env 미로드 시 ValidationError.",
    )

    langfuse_enabled: bool = Field(default=True)
    langfuse_host: str = Field(default="http://localhost:3000")
    langfuse_public_key: str = Field(default="")
    langfuse_secret_key: str = Field(default="")

    mcp_transport: McpTransport = Field(
        default="stdio",
        description="MCP transport: stdio (Inspector/로컬) 또는 sse (백엔드 연동). "
        "다른 값은 pydantic validation 단계에서 즉시 거부.",
    )
    mcp_sse_host: str = Field(default="0.0.0.0")
    mcp_sse_port: int = Field(default=8090)


@lru_cache
def get_settings() -> Settings:
    """싱글턴 settings. 테스트에서는 monkeypatch + cache_clear 로 초기화."""
    return Settings()
