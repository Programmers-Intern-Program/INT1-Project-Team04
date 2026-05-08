"""채널별 알림 렌더링 전에 검증하는 표준 브리핑 계약 모델."""

from __future__ import annotations

from urllib.parse import urlparse

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator

REAL_ESTATE_DOMAINS = {"real-estate", "real_estate", "부동산"}
RECRUITMENT_DOMAINS = {"recruitment", "job", "jobs", "채용"}
REAL_ESTATE_AVERAGE_LABEL_KEYWORDS = ("매매", "가격", "보증", "월세")


class BriefingChange(BaseModel):
    """알림 본문에 표시할 변화 항목."""

    model_config = ConfigDict(populate_by_name=True)

    label: str = Field(min_length=1)
    value: str = Field(min_length=1)
    previous: str | None = None
    current: str | None = None


class BriefingWatchInfo(BaseModel):
    """사용자가 구독한 감시 대상과 조건을 표시하기 위한 정보."""

    model_config = ConfigDict(populate_by_name=True)

    target: str = Field(min_length=1)
    condition: str | None = None
    observed_at: str | None = Field(
        default=None,
        validation_alias=AliasChoices("observedAt", "observed_at"),
        serialization_alias="observedAt",
    )
    region: str | None = None
    deal_period: str | None = Field(
        default=None,
        validation_alias=AliasChoices("dealPeriod", "deal_period"),
        serialization_alias="dealPeriod",
    )
    keyword: str | None = None
    data_source: str | None = Field(
        default=None,
        validation_alias=AliasChoices("dataSource", "data_source"),
        serialization_alias="dataSource",
    )


class BriefingSource(BaseModel):
    """알림 판단 근거로 노출할 출처 정보."""

    label: str = Field(min_length=1)
    url: str | None = None
    description: str | None = None

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str | None) -> str | None:
        if value is None:
            return None
        url = value.strip()
        if not url or any(character.isspace() for character in url):
            return None
        if url.startswith("www."):
            url = f"https://{url}"
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return None
        return url


class NotificationBriefing(BaseModel):
    """AI가 자유 본문 대신 전달하는 구조화 브리핑 계약."""

    model_config = ConfigDict(populate_by_name=True)

    domain: str = Field(min_length=1)
    title: str = Field(min_length=1)
    summary: str = Field(min_length=1)
    changes: list[BriefingChange] = Field(min_length=1)
    watch_info: BriefingWatchInfo = Field(
        validation_alias=AliasChoices("watchInfo", "watch_info"),
        serialization_alias="watchInfo",
    )
    sources: list[BriefingSource] = Field(default_factory=list)
    interpretation: str = Field(min_length=1)

    @model_validator(mode="after")
    def validate_domain_contract(self) -> NotificationBriefing:
        domain = self.domain.strip().lower()
        self._validate_substance()
        if domain in RECRUITMENT_DOMAINS and not any(source.url for source in self.sources):
            raise ValueError("recruitment briefing source url is required")
        if domain in REAL_ESTATE_DOMAINS:
            self._validate_real_estate_contract()
        return self

    def _validate_substance(self) -> None:
        """성의없는 한두 줄 브리핑은 provider 발송 전에 차단한다."""
        title = self.title.strip()
        summary = self.summary.strip()
        interpretation = self.interpretation.strip()
        if len(title) < 6:
            raise ValueError("briefing is too terse: title")
        if len(summary) < 12:
            raise ValueError("briefing is too terse: summary")
        if len(interpretation) < 8:
            raise ValueError("briefing is too terse: interpretation")
        if len(self.changes) < 2:
            raise ValueError("briefing is too terse: changes")

    def _validate_real_estate_contract(self) -> None:
        if not self.watch_info.region:
            raise ValueError("real-estate briefing requires region")
        if not self.watch_info.deal_period:
            raise ValueError("real-estate briefing requires deal_period")
        if len(self.changes) < 3:
            raise ValueError("briefing is too terse: real-estate briefing requires at least 3 changes")

        labels = [change.label.strip() for change in self.changes]
        values = [change.value.strip() for change in self.changes]
        if not any(
            "평균" in label and any(keyword in label for keyword in REAL_ESTATE_AVERAGE_LABEL_KEYWORDS)
            for label in labels
        ):
            raise ValueError("real-estate briefing requires average metric")
        if not any("변화율" in label or "%" in value for label, value in zip(labels, values, strict=False)):
            raise ValueError("real-estate briefing requires change rate")
        if not any(("거래" in label and "건수" in label) or label == "건수" for label in labels):
            raise ValueError("real-estate briefing requires transaction count")
