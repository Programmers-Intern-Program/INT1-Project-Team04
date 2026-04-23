"""SQLAlchemy DeclarativeBase 공통 베이스.

모든 ORM 모델은 이 Base 를 상속한다.
metadata.create_all / drop_all 호출 시 등록된 모든 테이블이 한번에 처리된다.
"""

from datetime import datetime

from sqlalchemy import DateTime, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    """전역 공통 베이스."""


class TimestampMixin:
    """모든 테이블에 created_at / updated_at 자동 부여."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
