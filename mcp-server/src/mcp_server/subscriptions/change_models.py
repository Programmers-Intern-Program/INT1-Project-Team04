"""구독 변화 비교 MCP 도구 입출력 모델."""

from typing import Any, Literal

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class SubscriptionChangeInput(BaseModel):
    """compare_subscription_change 도구 입력.

    Spring AI 가 기존 데이터 조회 도구 응답 전체를 current 필드로 전달한다.
    """

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    subscription_id: str = Field(
        validation_alias=AliasChoices("subscriptionId", "subscription_id"),
        serialization_alias="subscriptionId",
        min_length=1,
    )
    domain: str = Field(min_length=1)
    query: str | None = None
    params: dict[str, Any] = Field(default_factory=dict)
    current: dict[str, Any]


class SummaryDiff(BaseModel):
    """기준 요약과 현재 요약의 단일 필드 변화."""

    field: str
    baseline_value: int | float | str | bool | None
    current_value: int | float | str | bool | None
    delta: float | None = None
    change_rate: float | None = None
    direction: Literal["increase", "decrease", "changed"] = "changed"


class SubscriptionChangeResult(BaseModel):
    """AI 브리핑 생성을 위한 구조화된 변화 비교 결과."""

    baseline_initialized: bool
    changed: bool
    subscription_id: str = Field(serialization_alias="subscriptionId")
    domain: str
    params_hash: str
    baseline_summary: dict[str, Any]
    current_summary: dict[str, Any]
    diffs: list[SummaryDiff] = Field(default_factory=list)
    briefing_facts: list[str] = Field(default_factory=list)
