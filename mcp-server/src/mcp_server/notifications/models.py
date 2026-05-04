"""알림 발송 요청/응답 모델.

Spring AI 가 만드는 tool input 은 백엔드 필드명과 짧은 별칭이 섞일 수 있다.
이 모듈은 두 입력 형태를 모두 Pydantic 검증 단계에서 정규화해 도구 구현부가
channel/target/subscription_id 만 다루도록 한다.
"""

from enum import StrEnum
from typing import Any

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class NotificationChannel(StrEnum):
    """백엔드 구독 컨텍스트가 전달하는 알림 채널 이름."""

    TELEGRAM_DM = "TELEGRAM_DM"
    DISCORD_DM = "DISCORD_DM"
    EMAIL = "EMAIL"


class NotificationRequest(BaseModel):
    """알림 발송 MCP 도구 입력 모델.

    SubscriptionContext 가 notificationChannel/notificationTarget/subscriptionId 를 쓰므로
    백엔드 필드명을 우선 허용한다. 짧은 별칭도 함께 받아 모델이 만든 입력 이름 차이를
    MCP 쪽에서 흡수한다.
    """

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    channel: NotificationChannel = Field(
        validation_alias=AliasChoices("notificationChannel", "channel"),
        serialization_alias="notificationChannel",
        description="알림 채널. 구독의 notificationChannel 값을 그대로 사용한다.",
    )
    target: str = Field(
        validation_alias=AliasChoices("notificationTarget", "target"),
        serialization_alias="notificationTarget",
        min_length=1,
        description="알림 수신 대상. 구독의 notificationTarget 값을 그대로 사용한다.",
    )
    title: str | None = Field(default=None, description="짧은 알림 제목.")
    message: str = Field(min_length=1, description="조건 충족 근거를 포함한 알림 본문.")
    subscription_id: int | None = Field(
        default=None,
        validation_alias=AliasChoices("subscriptionId", "subscription_id"),
        serialization_alias="subscriptionId",
        description="구독 ID. 호출 컨텍스트에 있으면 전달한다.",
    )
    idempotency_key: str | None = Field(
        default=None,
        description="호출자가 제공할 수 있으면 전달하는 안정적인 이벤트 키.",
    )
    metadata: dict[str, Any] = Field(default_factory=dict)


class NotificationResult(BaseModel):
    """모델에 반환하는 구조화된 외부 제공자 발송 결과."""

    sent: bool
    channel: NotificationChannel
    target: str
    provider: str
    provider_message_id: str | None = None
    retryable: bool = False
    status_code: int | None = None
    error: str | None = None
