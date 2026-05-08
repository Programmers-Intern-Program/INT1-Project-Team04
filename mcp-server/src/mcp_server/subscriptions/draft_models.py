"""구독 초안 구조화 입출력 모델.

백엔드 AI 파서 결과는 자연어 이해에 가까운 초안이고, MCP 서버의 실행 도구는
region/deal_ymd 같은 명확한 입력 스키마를 요구한다. 이 모듈은 그 사이 계약을
고정한다.

원칙:
- 백엔드는 userMessage + ParsedTask + previousDraft 만 넘긴다.
- MCP 서버는 domain/intent/tool/parameters/missingFields 를 도메인 규칙으로 다시 판단한다.
- Java camelCase 와 Python snake_case 를 모두 허용해 양쪽 리팩토링의 결합도를 낮춘다.
"""

from typing import Any

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


# ─────────────────────────────────────────────
# 백엔드 → MCP 입력 모델
# ─────────────────────────────────────────────

class ParsedTaskDraft(BaseModel):
    """백엔드 AI 파서가 만든 단일 작업 초안.

    AI 파서 출력은 아직 실행 계약이 아니다. 예를 들어 "아파트 변경"처럼
    query 가 모호해도 parser 는 부동산으로 분류할 수 있으므로, MCP 정규화 단계가
    dealType/region/condition 누락 여부를 다시 판정한다.
    """

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    intent: str | None = None
    domain_name: str | None = Field(
        default=None,
        validation_alias=AliasChoices("domainName", "domain_name"),
        serialization_alias="domainName",
    )
    query: str | None = None
    condition: str | None = None
    cron_expr: str | None = Field(
        default=None,
        validation_alias=AliasChoices("cronExpr", "cron_expr"),
        serialization_alias="cronExpr",
    )
    channel: str | None = None
    api_type: str | None = Field(
        default=None,
        validation_alias=AliasChoices("apiType", "api_type"),
        serialization_alias="apiType",
    )
    target: str | None = None
    urls: list[str] = Field(default_factory=list)
    confidence: float = 0.0
    needs_confirmation: bool = Field(
        default=False,
        validation_alias=AliasChoices("needsConfirmation", "needs_confirmation"),
        serialization_alias="needsConfirmation",
    )
    confirmation_question: str | None = Field(
        default=None,
        validation_alias=AliasChoices("confirmationQuestion", "confirmation_question"),
        serialization_alias="confirmationQuestion",
    )


class PreviousSubscriptionDraft(BaseModel):
    """백엔드에 저장되어 있던 이전 구독 초안.

    멀티 턴 대화에서 사용자가 "매매로 해줘", "5% 이상 상승"처럼 짧게 답할 수 있으므로
    이전 초안의 domainName, monitoringParams 를 MCP 정규화 단계에 같이 전달한다.
    """

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    query: str | None = None
    domain_name: str | None = Field(
        default=None,
        validation_alias=AliasChoices("domainName", "domain_name"),
        serialization_alias="domainName",
    )
    intent: str | None = None
    tool_name: str | None = Field(
        default=None,
        validation_alias=AliasChoices("toolName", "tool_name"),
        serialization_alias="toolName",
    )
    monitoring_params: dict[str, str] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("monitoringParams", "monitoring_params"),
        serialization_alias="monitoringParams",
    )
    notification_channel: str | None = Field(
        default=None,
        validation_alias=AliasChoices("notificationChannel", "notification_channel"),
        serialization_alias="notificationChannel",
    )
    notification_target_address: str | None = Field(
        default=None,
        validation_alias=AliasChoices("notificationTargetAddress", "notification_target_address"),
        serialization_alias="notificationTargetAddress",
    )


class SubscriptionDraftNormalizationInput(BaseModel):
    """normalize_subscription_draft 도구 입력.

    FastMCP 도구는 실제 호출 시 {"input": {...}} 래퍼로 이 모델을 받는다.
    extra="forbid" 로 최상위 입력을 고정해 도구 계약이 느슨해지지 않게 한다.
    """

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    user_message: str = Field(
        validation_alias=AliasChoices("userMessage", "user_message"),
        serialization_alias="userMessage",
    )
    task: ParsedTaskDraft
    previous_draft: PreviousSubscriptionDraft | None = Field(
        default=None,
        validation_alias=AliasChoices("previousDraft", "previous_draft"),
        serialization_alias="previousDraft",
    )


# ─────────────────────────────────────────────
# MCP → 백엔드 출력 모델
# ─────────────────────────────────────────────

class NormalizedSubscriptionDraft(BaseModel):
    """MCP가 도메인별 실행 계약으로 정규화한 구독 초안.

    parameters 는 실제 조회 도구에 전달 가능한 도메인 파라미터이고,
    missingFields 는 백엔드가 구독 저장을 막아야 하는 미충족 필드 목록이다.
    cadence/notificationChannel 은 사용자별 전달 설정이라 이 모델에서 확정하지 않는다.
    """

    model_config = ConfigDict(populate_by_name=True)

    query: str | None = None
    domain_name: str | None = Field(serialization_alias="domainName")
    intent: str | None = None
    tool_name: str | None = Field(default=None, serialization_alias="toolName")
    parameters: dict[str, str] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list, serialization_alias="missingFields")
    question: str = ""
    confidence: float = 0.0
    metadata: dict[str, Any] = Field(default_factory=dict)
