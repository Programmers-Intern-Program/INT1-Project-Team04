"""initial schema

MCP 내부 DB 초기 스키마. mcp_server.db.models 의 5개 테이블을 생성한다.
(기존 scripts/create_tables.py 의 Base.metadata.create_all 을 대체)

Revision ID: 1a4524728212
Revises:
Create Date: 2026-05-11 16:55:18.837926

"""
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "1a4524728212"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# models.py 의 JsonColumn 과 동일: Postgres 에서는 JSONB, 그 외에는 JSON.
_JSON = sa.JSON().with_variant(postgresql.JSONB(), "postgresql")


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "api_source",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("tool_name", sa.String(length=100), nullable=False),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("url_template", sa.Text(), nullable=False),
        sa.Column("param_schema", _JSON, nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_api_source_tool_name"), "api_source", ["tool_name"], unique=False)

    op.create_table(
        "crawl_source",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("tool_name", sa.String(length=100), nullable=False),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("base_url", sa.Text(), nullable=False),
        sa.Column("css_selector", sa.Text(), nullable=True),
        sa.Column("headers", _JSON, nullable=True),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        op.f("ix_crawl_source_tool_name"), "crawl_source", ["tool_name"], unique=False
    )

    op.create_table(
        "api_cache",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("source_id", sa.Integer(), nullable=False),
        sa.Column("site_url", sa.String(length=2048), nullable=False),
        sa.Column("api_type", sa.String(length=50), nullable=False),
        sa.Column("content", sa.Text(), nullable=True),
        sa.Column(
            "cached_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.current_timestamp(),
            nullable=False,
        ),
        sa.Column("expired_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["source_id"], ["api_source.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("site_url"),
    )

    op.create_table(
        "crawl_cache",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("crawl_source_id", sa.Integer(), nullable=False),
        sa.Column("url", sa.String(length=2048), nullable=False),
        sa.Column("content", sa.Text(), nullable=True),
        sa.Column(
            "crawled_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.current_timestamp(),
            nullable=False,
        ),
        sa.Column("expired_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["crawl_source_id"], ["crawl_source.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("url"),
    )

    op.create_table(
        "subscription_snapshot_state",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("subscription_id", sa.String(length=64), nullable=False),
        sa.Column("domain", sa.String(length=100), nullable=False),
        sa.Column("query", sa.Text(), nullable=True),
        sa.Column("params_hash", sa.String(length=64), nullable=False),
        sa.Column("baseline_summary", _JSON, nullable=False),
        sa.Column("baseline_content", sa.Text(), nullable=True),
        sa.Column("baseline_captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("latest_summary", _JSON, nullable=True),
        sa.Column("latest_content", sa.Text(), nullable=True),
        sa.Column("latest_captured_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.current_timestamp(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.current_timestamp(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "subscription_id",
            "params_hash",
            name="uq_subscription_snapshot_state_subscription_params",
        ),
    )
    op.create_index(
        op.f("ix_subscription_snapshot_state_subscription_id"),
        "subscription_snapshot_state",
        ["subscription_id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_subscription_snapshot_state_params_hash"),
        "subscription_snapshot_state",
        ["params_hash"],
        unique=False,
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(
        op.f("ix_subscription_snapshot_state_params_hash"),
        table_name="subscription_snapshot_state",
    )
    op.drop_index(
        op.f("ix_subscription_snapshot_state_subscription_id"),
        table_name="subscription_snapshot_state",
    )
    op.drop_table("subscription_snapshot_state")
    op.drop_table("crawl_cache")
    op.drop_table("api_cache")
    op.drop_index(op.f("ix_crawl_source_tool_name"), table_name="crawl_source")
    op.drop_table("crawl_source")
    op.drop_index(op.f("ix_api_source_tool_name"), table_name="api_source")
    op.drop_table("api_source")
