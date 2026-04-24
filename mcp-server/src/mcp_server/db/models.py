"""MCP 내부 DB 모델 (팀 확정 ERD).

Main DB 와 분리된 MCP 서버 전용 스키마.
경계 원칙: 메인 백엔드는 이 테이블을 직접 조회하지 않는다.

테이블 4종:
- api_source: 공식 공공 API 등록 (tool_name 으로 도구 구분)
- crawl_source: 크롤링 대상 등록
- api_cache: API 응답 캐시 (DDL만, MVP 단계 read/write 없음)
- crawl_cache: 크롤링 결과 캐시 (DDL만, MVP 단계 read/write 없음)

원본 ERD는 MySQL 문법으로 정의됨. SQLAlchemy 로 Postgres/SQLite 양쪽에서 동작하도록 매핑.
- AUTO_INCREMENT  → SQLAlchemy Integer PK 의 기본 자동증가
- TINYINT(1)      → Boolean
- VARCHAR(36) PK  → 문자열 UUID (uuid4 자동 생성)
- JSON            → Postgres 에서는 JSONB, 그 외에는 JSON
"""

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from mcp_server.db.base import Base

# Postgres 에서는 JSONB, 그 외 (SQLite 테스트 등) JSON 으로 자동 폴백
JsonColumn = JSON().with_variant(JSONB(), "postgresql")


def _new_uuid() -> str:
    return str(uuid.uuid4())


class ApiSource(Base):
    """공식 공공 API 데이터 소스 메타데이터.

    실제 호출은 sources.api_source_service.fetch(source_id, params) 를 통해서만 한다.
    """

    __tablename__ = "api_source"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    tool_name: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    url_template: Mapped[str] = mapped_column(Text, nullable=False)
    param_schema: Mapped[dict[str, Any] | None] = mapped_column(JsonColumn, nullable=True)


class CrawlSource(Base):
    """크롤링 대상 데이터 소스 메타데이터.

    실제 호출은 sources.crawl_source_service.fetch(source_id, params) 를 통해서만 한다.
    is_active=False 인 source 는 호출 시점에 거절한다.
    """

    __tablename__ = "crawl_source"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    tool_name: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    base_url: Mapped[str] = mapped_column(Text, nullable=False)
    css_selector: Mapped[str | None] = mapped_column(Text, nullable=True)
    headers: Mapped[dict[str, Any] | None] = mapped_column(JsonColumn, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)


class ApiCache(Base):
    """API 응답 캐시 — DDL만 생성. MVP 단계 read/write 하지 않는다.

    캐시 백엔드 결정(Postgres vs Redis)은 호출 패턴 데이터 누적 후 별도 결정.
    """

    __tablename__ = "api_cache"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_new_uuid)
    source_id: Mapped[int] = mapped_column(
        ForeignKey("api_source.id"),
        nullable=False,
    )
    site_url: Mapped[str] = mapped_column(String(2048), nullable=False, unique=True)
    api_type: Mapped[str] = mapped_column(String(50), nullable=False)
    content: Mapped[str | None] = mapped_column(Text, nullable=True)
    cached_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.current_timestamp(),
    )
    expired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class CrawlCache(Base):
    """크롤링 결과 캐시 — DDL만 생성. MVP 단계 read/write 하지 않는다."""

    __tablename__ = "crawl_cache"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_new_uuid)
    crawl_source_id: Mapped[int] = mapped_column(
        ForeignKey("crawl_source.id"),
        nullable=False,
    )
    url: Mapped[str] = mapped_column(String(2048), nullable=False, unique=True)
    content: Mapped[str | None] = mapped_column(Text, nullable=True)
    crawled_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.current_timestamp(),
    )
    expired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


__all__ = ["ApiSource", "CrawlSource", "ApiCache", "CrawlCache"]
