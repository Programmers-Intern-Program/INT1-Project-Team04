"""MCP 서버 환경 설정.

환경 변수 → pydantic-settings → 전역 settings 객체로 노출.
모든 모듈은 직접 os.environ 을 읽지 말고 이 settings 를 import 한다.
"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """애플리케이션 설정."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    pg_url: str = Field(
        default="postgresql+asyncpg://devuser:devpass@localhost:5432/mcp_dev",
        description="MCP 내부 DB 접속 URL (asyncpg 드라이버)",
    )

    langfuse_enabled: bool = Field(default=True)
    langfuse_host: str = Field(default="http://localhost:3000")
    langfuse_public_key: str = Field(default="")
    langfuse_secret_key: str = Field(default="")

    mcp_transport: str = Field(default="stdio", description="stdio | sse")
    mcp_sse_host: str = Field(default="0.0.0.0")
    mcp_sse_port: int = Field(default=8090)


@lru_cache
def get_settings() -> Settings:
    """싱글턴 settings. 테스트에서는 monkeypatch + cache_clear 로 초기화."""
    return Settings()
