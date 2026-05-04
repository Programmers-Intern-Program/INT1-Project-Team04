"""구독 초안을 MCP 도구 실행 계약으로 정규화한다.

역할:
- 백엔드 AI 파서가 만든 자연어 초안을 MCP 서버가 지원하는 도메인 계약으로 다시 구조화한다.
- 현재 실행 가능한 구독은 부동산 아파트 매매 실거래가 중심이므로
  search_house_price 입력에 필요한 region/condition/dealType 여부를 여기서 판정한다.
- cadence/notificationChannel/notificationTarget 은 백엔드 사용자 설정 영역이므로
  이 모듈에서는 missingFields 로 다루지 않는다.

안전장치:
- "강남구 아파트 변경"처럼 자료유형이 빠진 요청은 매매로 추정하지 않고 dealType 을 요구한다.
- 전세/월세 요청은 search_house_price 로 변환하지 않고 unsupportedCapability 로 돌려준다.
- 채용/법률/경매는 도메인 분류는 유지하되 planned capability 로 응답해 UX 와 실행 범위를 맞춘다.
"""

from __future__ import annotations

import re
from decimal import Decimal

from mcp_server.subscriptions.draft_models import (
    NormalizedSubscriptionDraft,
    SubscriptionDraftNormalizationInput,
)

_TOOL_APT_TRADE = "search_house_price"
_PENDING_DEAL_TYPE_CONFIRMATION = "pendingDealTypeConfirmation"

_REGION = re.compile(r"([가-힣]+(?:특별자치시|특별자치도|특별시|광역시|시|군|구))")
_THRESHOLD = re.compile(r"(\d+(?:\.\d+)?)\s*(%|퍼센트|프로|만원|억)?")

_SIDO_ONLY_REGIONS = {
    "서울특별시",
    "부산광역시",
    "대구광역시",
    "인천광역시",
    "광주광역시",
    "대전광역시",
    "울산광역시",
    "제주특별자치도",
}

_REGION_ALIASES = {
    "강남": "강남구",
    "서초": "서초구",
    "송파": "송파구",
    "마포": "마포구",
    "성남": "성남시",
    "안산": "안산시",
}

_PLANNED_DOMAINS = {
    "law-regulation": "법률/규제",
    "recruitment": "채용",
    "auction": "경매/희소매물",
}


# ─────────────────────────────────────────────
# 공개 정규화 함수
# ─────────────────────────────────────────────

def normalize_subscription_draft(
    input_model: SubscriptionDraftNormalizationInput,
) -> NormalizedSubscriptionDraft:
    """AI 파서 초안을 도메인별 감시 파라미터로 구조화한다.

    반환값은 백엔드가 그대로 저장할 수 있는 값이 아니라, 저장 가능 여부를 판단하기 위한
    도메인 구조화 결과다. missing_fields 가 비어 있을 때만 실제 구독 생성 단계로 넘어간다.
    """

    task = input_model.task
    previous = input_model.previous_draft

    # domainName 은 파서가 한국어 라벨로 줄 수도 있고, 이전 draft 에서 이어받을 수도 있다.
    # 이후 분기는 모두 canonical id(real-estate 등)를 기준으로 처리한다.
    domain_name = _canonical_domain_name(task.domain_name)
    if not domain_name and previous is not None:
        domain_name = previous.domain_name

    # 사용자가 후속 턴에서 짧게 답한 경우 같은 도메인의 이전 초안을 재사용한다.
    # 도메인이 바뀌면 region/condition 을 잘못 이어붙일 수 있으므로 재사용하지 않는다.
    can_reuse_previous = previous is not None and domain_name == previous.domain_name
    query = task.query if not _is_blank(task.query) else previous.query if can_reuse_previous else task.query
    parse_intent = (
        task.intent
        if not _is_blank(task.intent)
        else "create"
        if can_reuse_previous
        else ""
    )
    params: dict[str, str] = {}
    missing: list[str] = []

    # 도메인 자체를 확정할 수 없거나 parser 가 reject 한 요청은 백엔드 저장 단계로 보내지 않는다.
    if domain_name is None or parse_intent == "reject":
        return _draft(
            query=query,
            domain_name=domain_name,
            intent=parse_intent,
            parameters=params,
            missing_fields=["unsupportedDomain"],
            question="지원하지 않는 요청이에요.",
            confidence=task.confidence,
        )

    # 현재 채팅 플로우는 구독 생성(create) 전용이다. 수정/삭제는 별도 액션 플로우가 필요하다.
    if parse_intent != "create":
        return _draft(
            query=query,
            domain_name=domain_name,
            intent=parse_intent,
            parameters=params,
            missing_fields=["unsupportedIntent"],
            question="알림 수정과 삭제는 아직 채팅 생성 플로우에서 처리하지 않아요.",
            confidence=task.confidence,
        )

    # planned 도메인은 사용자의 의도를 보존하되 실제 MCP 조회 도구로는 연결하지 않는다.
    if domain_name in _PLANNED_DOMAINS:
        return _draft(
            query=query,
            domain_name=domain_name,
            intent=None,
            parameters=params,
            missing_fields=["unsupportedCapability"],
            question=(
                f"{_PLANNED_DOMAINS[domain_name]} 알림은 준비 중이에요. "
                "현재는 부동산 아파트 매매 실거래가 알림만 만들 수 있어요."
            ),
            confidence=task.confidence,
        )

    # registry 밖 도메인은 프롬프트가 과하게 추론한 결과일 수 있으므로 unsupported 로 막는다.
    if domain_name != "real-estate":
        return _draft(
            query=query,
            domain_name=domain_name,
            intent=parse_intent,
            parameters=params,
            missing_fields=["unsupportedDomain"],
            question="지원하지 않는 요청이에요.",
            confidence=task.confidence,
        )

    intent = "apartment_trade_price"
    params["dealYmdPolicy"] = "LATEST_AVAILABLE_MONTH"

    region = _extract_region(query, task.target)
    if region is not None:
        params["region"] = region

    # 현재 부동산 구독 실행 도구는 아파트 "매매" 실거래가 기준이다.
    # AI 가 만든 query/target 은 원문에 없는 매매/전세 표현을 보탤 수 있으므로 거래유형은 사용자 원문으로 판단한다.
    text = input_model.user_message if not _is_blank(input_model.user_message) else query or ""
    if _mentions_rent(text):
        params[_PENDING_DEAL_TYPE_CONFIRMATION] = "true"
        return _draft(
            query=query,
            domain_name=domain_name,
            intent=intent,
            parameters=params,
            missing_fields=["unsupportedCapability"],
            question="현재는 아파트 매매 실거래가 알림만 만들 수 있어요. 매매 실거래가 알림으로 만들까요?",
            confidence=task.confidence,
        )

    # "아파트 가격/시세/집값/변동"은 매매/전월세 중 무엇인지 불명확하다.
    # search_house_price 로 실행 가능한 "매매 실거래가"가 명시될 때까지 구독 생성을 보류한다.
    if (
        can_reuse_previous
        and previous is not None
        and previous.monitoring_params.get(_PENDING_DEAL_TYPE_CONFIRMATION) == "true"
    ) or _requires_explicit_apartment_deal_type(text):
        missing.append("dealType")
        params[_PENDING_DEAL_TYPE_CONFIRMATION] = "true"

    # MCP 도구 입력은 시군구 단위 region 을 요구한다. 서울특별시 같은 시도 단독 입력은 모호하므로 제외한다.
    region = params.get("region")
    if region is None and can_reuse_previous:
        region = previous.monitoring_params.get("region") if previous is not None else None
    if region is None:
        missing.append("region")
    else:
        params["region"] = region

    # condition 은 비교/알림 트리거에 필요한 구조화 파라미터로 변환한다.
    # 이전 초안에 이미 구조화 조건이 있으면 후속 턴에서 재사용한다.
    condition = _parse_condition(task.condition)
    if condition is None and can_reuse_previous and previous is not None:
        condition = _condition_from_parameters(previous.monitoring_params)
    if condition is None:
        missing.append("condition")
    else:
        params.update(condition)

    return _draft(
        query=_apartment_trade_query(query, params) if not missing else query,
        domain_name=domain_name,
        intent=intent,
        tool_name=_TOOL_APT_TRADE if "dealType" not in missing else None,
        parameters=params,
        missing_fields=missing,
        question=_question_for_missing(missing),
        confidence=task.confidence,
    )


# ─────────────────────────────────────────────
# 응답 빌더
# ─────────────────────────────────────────────

def _draft(
    *,
    query: str | None,
    domain_name: str | None,
    intent: str | None,
    parameters: dict[str, str],
    missing_fields: list[str],
    question: str,
    confidence: float,
    tool_name: str | None = None,
) -> NormalizedSubscriptionDraft:
    """공통 출력 모델 생성. by_alias 직렬화 시 Java 쪽 필드명(camelCase)으로 내려간다."""
    return NormalizedSubscriptionDraft(
        query=query,
        domain_name=domain_name,
        intent=intent,
        tool_name=tool_name,
        parameters=parameters,
        missing_fields=missing_fields,
        question=question,
        confidence=confidence,
        metadata={"normalizer": "mcp-server"},
    )


# ─────────────────────────────────────────────
# 도메인/지역 정규화
# ─────────────────────────────────────────────

def _canonical_domain_name(value: str | None) -> str | None:
    text = (value or "").strip()
    if text in {"부동산", "real-estate"}:
        return "real-estate"
    if text in {"법률", "법률/규제", "law-regulation"}:
        return "law-regulation"
    if text in {"채용", "recruitment"}:
        return "recruitment"
    if text in {"경매", "경매/희소매물", "auction"}:
        return "auction"
    return text or None


def _extract_region(query: str | None, target: str | None) -> str | None:
    return _extract_supported_region(query) or _extract_supported_region(target) or _extract_alias_region(
        query,
        target,
    )


def _extract_supported_region(text: str | None) -> str | None:
    for match in _REGION.finditer(text or ""):
        region = match.group(1)
        if region not in _SIDO_ONLY_REGIONS:
            return region
    return None


def _extract_alias_region(query: str | None, target: str | None) -> str | None:
    text = f"{query or ''} {target or ''}"
    for alias, region in _REGION_ALIASES.items():
        if alias in text:
            return region
    return None


# ─────────────────────────────────────────────
# 조건 정규화
# ─────────────────────────────────────────────

def _parse_condition(raw: str | None) -> dict[str, str] | None:
    """자연어 조건을 백엔드 MonitoringChangeDetector 가 읽는 파라미터 맵으로 변환한다."""
    text = (raw or "").strip()
    if not text:
        return None
    match = _THRESHOLD.search(text)
    if match is None:
        return None

    threshold = Decimal(match.group(1)).normalize()
    return {
        "conditionMetric": "AVG_PRICE",
        "conditionDirection": _condition_direction(text),
        "conditionOperator": _condition_operator(text),
        "conditionThreshold": format(threshold, "f"),
        "conditionUnit": _condition_unit(match.group(2)),
    }


def _condition_from_parameters(parameters: dict[str, str]) -> dict[str, str] | None:
    keys = [
        "conditionMetric",
        "conditionDirection",
        "conditionOperator",
        "conditionThreshold",
        "conditionUnit",
    ]
    if all(parameters.get(key) for key in keys):
        return {key: parameters[key] for key in keys}
    return _parse_condition(parameters.get("condition"))


# ─────────────────────────────────────────────
# 모호성/질문 처리
# ─────────────────────────────────────────────

def _condition_direction(text: str) -> str:
    if any(word in text for word in ["하락", "떨어", "내리"]):
        return "DOWN"
    if any(word in text for word in ["상승", "오르", "올라"]):
        return "UP"
    return "ANY"


def _condition_operator(text: str) -> str:
    if "미만" in text:
        return "LT"
    if "이하" in text:
        return "LTE"
    if "초과" in text:
        return "GT"
    return "GTE"


def _condition_unit(raw: str | None) -> str:
    if raw == "만원":
        return "MANWON"
    if raw == "억":
        return "EOK"
    return "PERCENT"


def _requires_explicit_apartment_deal_type(text: str) -> bool:
    """아파트 가격 요청이 매매/전월세 중 무엇인지 명시됐는지 확인한다."""
    lowered = text.lower()
    if _mentions_rent(lowered) or "매매" in lowered:
        return False
    return any(word in lowered for word in ["아파트", "가격", "시세", "집값", "실거래가", "변경", "변동"])


def _mentions_rent(text: str) -> bool:
    return any(word in text for word in ["전월세", "전세", "월세"])


def _apartment_trade_query(query: str | None, params: dict[str, str]) -> str | None:
    """매매가 확정된 모호 query 는 확인 화면에 보일 표준 query 로 보정한다."""
    if query and not _requires_explicit_apartment_deal_type(query):
        return query
    region = params.get("region")
    if region:
        return f"{region} 아파트 매매 실거래가"
    return query


def _question_for_missing(missing: list[str]) -> str:
    """백엔드가 그대로 사용자에게 보여줄 수 있는 도메인 추가 질문을 만든다."""
    if "region" in missing:
        return "어느 지역의 아파트 매매 실거래가를 확인할까요?"
    if "dealType" in missing:
        return "아파트 가격은 매매/전세/월세 중 어떤 기준인가요? 현재는 매매 실거래가 알림만 만들 수 있어요."
    if "condition" in missing:
        return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등"
    return ""


def _joined_text(*values: str | None) -> str:
    return " ".join(value for value in values if value)


def _is_blank(value: str | None) -> bool:
    return value is None or value.strip() == ""
