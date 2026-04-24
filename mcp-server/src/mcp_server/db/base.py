"""SQLAlchemy DeclarativeBase 공통 베이스.

모든 ORM 모델은 이 Base 를 상속한다.
metadata.create_all / drop_all 호출 시 등록된 모든 테이블이 한번에 처리된다.
"""

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """전역 공통 베이스."""
