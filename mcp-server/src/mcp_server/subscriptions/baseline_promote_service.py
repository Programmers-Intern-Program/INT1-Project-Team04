"""구독 baseline 승격 서비스.

알림 메시지의 1회용 링크를 사용자가 클릭하면, 메인 서버가 이 서비스를 통해
대상 스냅샷 행의 baseline_summary 를 latest_summary 로 덮어쓴다.
이후 compare_subscription_change 호출은 이 시점을 새 기준으로 삼는다.

메인 DB 토큰 테이블은 메인 서버에서만 검증·소비한다.
이 서비스는 (subscription_id [, params_hash]) 만 받는다
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select

from mcp_server.db.models import SubscriptionSnapshotState
from mcp_server.db.session import get_session
from mcp_server.subscriptions.baseline_promote_models import (
    PromoteSubscriptionBaselineInput,
    PromoteSubscriptionBaselineResult,
)


class BaselinePromoteService:
    """latest_summary 를 baseline_summary 로 승격시킨다."""

    async def promote(
        self, input_model: PromoteSubscriptionBaselineInput
    ) -> PromoteSubscriptionBaselineResult:
        now = datetime.now(UTC)
        async with get_session() as session:
            stmt = select(SubscriptionSnapshotState).where(
                SubscriptionSnapshotState.subscription_id == input_model.subscription_id,
            )
            if input_model.params_hash:
                stmt = stmt.where(
                    SubscriptionSnapshotState.params_hash == input_model.params_hash
                )
            rows = (await session.execute(stmt)).scalars().all()

            if not rows:
                return PromoteSubscriptionBaselineResult(
                    promoted=False,
                    subscription_id=input_model.subscription_id,
                    rows_updated=0,
                    promoted_at=now,
                    skipped_reason="snapshot row not found",
                )

            updated = 0
            for row in rows:
                if row.latest_summary is None:
                    # latest 가 한 번도 갱신되지 않은 행은 건너뛴다
                    # (baseline 이 이미 곧 최신값과 동일하므로 승격 의미 없음)
                    continue
                row.baseline_summary = row.latest_summary
                row.baseline_content = row.latest_content
                row.baseline_captured_at = row.latest_captured_at or now
                updated += 1

            if updated == 0:
                return PromoteSubscriptionBaselineResult(
                    promoted=False,
                    subscription_id=input_model.subscription_id,
                    rows_updated=0,
                    promoted_at=now,
                    skipped_reason="no row had latest_summary",
                )

            await session.commit()
            return PromoteSubscriptionBaselineResult(
                promoted=True,
                subscription_id=input_model.subscription_id,
                rows_updated=updated,
                promoted_at=now,
                skipped_reason=None,
            )
