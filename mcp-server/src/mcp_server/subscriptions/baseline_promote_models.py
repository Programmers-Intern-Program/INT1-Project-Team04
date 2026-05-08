"""promote_subscription_baseline 도구 입출력 모델."""

from datetime import datetime

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class PromoteSubscriptionBaselineInput(BaseModel):
    """promote_subscription_baseline 도구 입력.

    사용자가 알림 메시지의 1회용 GET 링크를 클릭하면 백엔드가 이 도구를 호출해
    latest_summary 를 baseline_summary 로 승격시킨다. 같은 구독에
    (subscription_id, params_hash) 행이 여러 개일 수 있으므로 params_hash 가
    주어지면 정확히 일치하는 행만 갱신한다.
    """

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    subscription_id: str = Field(
        validation_alias=AliasChoices("subscriptionId", "subscription_id"),
        serialization_alias="subscriptionId",
        description="메인 DB subscription.id",
        min_length=1,
    )
    params_hash: str | None = Field(
        default=None,
        validation_alias=AliasChoices("paramsHash", "params_hash"),
        serialization_alias="paramsHash",
        description="MCP DB 스냅샷 행 식별자. 미지정 시 해당 subscription_id 의 모든 행 갱신.",
    )


class PromoteSubscriptionBaselineResult(BaseModel):
    """promote_subscription_baseline 도구 응답."""

    promoted: bool
    subscription_id: str = Field(serialization_alias="subscriptionId")
    rows_updated: int
    promoted_at: datetime
    skipped_reason: str | None = None
