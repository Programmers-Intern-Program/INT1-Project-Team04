"""채용 구독 검색어 0건 검증과 후보 추천.

normalize_subscription_draft 자체는 문장 구조화가 책임이다. 이 모듈은 구조화된
채용 keyword 를 현재 채용 캐시/응답과 대조해 오타 가능성이 큰 경우에만
사용자 확인용 missing field 를 보강한다.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import Iterable

_log = logging.getLogger(__name__)

from mcp_server.config import get_settings
from mcp_server.domains.jobs.normalizer import PublicJobPosting, normalize_public_job
from mcp_server.sources import api_source_service
from mcp_server.subscriptions.draft_models import NormalizedSubscriptionDraft

_KEYWORD_CONFIRMATION = "keywordConfirmation"
_ZERO_RESULTS = "ZERO_RESULTS"
_TOOL_PUBLIC_JOB = "search_public_job"
_TOKEN = re.compile(r"[0-9A-Za-z가-힣+#.]{2,}")
_MIN_SIMILARITY = 0.66
_STOPWORDS = {
    "공개",
    "공개채용",
    "공고",
    "계약직",
    "기간제",
    "모집",
    "신입",
    "인턴",
    "정규직",
    "직원",
    "채용",
}


@dataclass(frozen=True)
class RecruitmentKeywordValidationResult:
    """채용 검색어 검증 결과."""

    keyword: str
    result_count: int
    suggested_keyword: str | None = None


async def validate_recruitment_keyword_for_draft(
    draft: NormalizedSubscriptionDraft,
) -> NormalizedSubscriptionDraft:
    """채용 초안에 0건 후보 추천 결과를 best-effort 로 반영한다."""
    if draft.domain_name != "recruitment":
        return draft
    if _KEYWORD_CONFIRMATION in draft.missing_fields:
        return draft
    if "keyword" in draft.missing_fields:
        return draft

    tool_name = draft.parameters.get("dataToolName") or draft.tool_name
    if tool_name != _TOOL_PUBLIC_JOB:
        return draft

    keyword = (draft.parameters.get("keyword") or "").strip()
    if not keyword:
        return draft

    validation = await validate_public_job_keyword(keyword)
    if validation is None:
        return draft
    return apply_recruitment_keyword_validation(draft, validation)


async def validate_public_job_keyword(
    keyword: str,
) -> RecruitmentKeywordValidationResult | None:
    """공공기관 채용 캐시에서 현재 검색어 결과 수와 후보를 계산한다.

    외부 API/캐시 접근 실패는 구독 등록 전체 실패가 아니라 검증 생략으로 처리한다.
    """
    keyword = keyword.strip()
    if not keyword:
        return None

    try:
        # tools.jobs 는 tools 패키지 로딩 중 subscriptions 도구를 함께 import 하므로 지연 import 한다.
        from mcp_server.tools.jobs import _resolve_source_id

        settings = get_settings()
        if not settings.moef_public_job_api_key:
            return None

        source_id = await _resolve_source_id(_TOOL_PUBLIC_JOB)
        raw = await api_source_service.fetch(
            source_id=source_id,
            params={
                "serviceKey": settings.moef_public_job_api_key,
                "pageNo": 1,
                "numOfRows": 100,
                "resultType": "json",
            },
        )
        postings = normalize_public_job(raw.content)
    except Exception as exc:
        _log.warning("채용 키워드 검증 실패 - keyword=%r: %s", keyword, exc)
        return None

    matched = [
        posting for posting in postings
        if _is_ongoing(posting) and keyword in (posting.title or "")
    ]
    if matched:
        return RecruitmentKeywordValidationResult(
            keyword=keyword,
            result_count=len(matched),
        )

    return RecruitmentKeywordValidationResult(
        keyword=keyword,
        result_count=0,
        suggested_keyword=suggest_recruitment_keyword(keyword, postings),
    )


def apply_recruitment_keyword_validation(
    draft: NormalizedSubscriptionDraft,
    validation: RecruitmentKeywordValidationResult | None,
) -> NormalizedSubscriptionDraft:
    """0건 + 후보가 있을 때 백엔드 확인용 missing field 를 추가한다."""
    if validation is None or validation.result_count > 0:
        return draft

    suggested = (validation.suggested_keyword or "").strip()
    if not suggested or suggested == validation.keyword:
        return draft

    parameters = dict(draft.parameters)
    parameters["keywordValidationStatus"] = _ZERO_RESULTS
    parameters["keywordOriginal"] = validation.keyword
    parameters["suggestedKeyword"] = suggested

    missing_fields = list(draft.missing_fields)
    if _KEYWORD_CONFIRMATION not in missing_fields:
        missing_fields.append(_KEYWORD_CONFIRMATION)

    question = (
        f"현재 '{validation.keyword}' 검색 결과가 없어요. "
        f"'{suggested}'를 뜻한 걸까요? 맞으면 '응', 아니면 원하는 채용 검색어를 다시 입력해 주세요."
    )
    return draft.model_copy(
        update={
            "parameters": parameters,
            "missing_fields": missing_fields,
            "question": question,
        }
    )


def suggest_recruitment_keyword(
    keyword: str,
    postings: Iterable[PublicJobPosting],
) -> str | None:
    """현재 공고 제목/NCS 토큰 중 오타 가능성이 가장 높은 후보를 찾는다."""
    normalized_keyword = _normalize_keyword(keyword)
    if not normalized_keyword:
        return None

    best_term: str | None = None
    best_score = 0.0
    for term in sorted(_candidate_terms(postings)):
        normalized_term = _normalize_keyword(term)
        if not normalized_term or normalized_term == normalized_keyword:
            continue
        if abs(len(normalized_term) - len(normalized_keyword)) > 2:
            continue
        score = SequenceMatcher(None, normalized_keyword, normalized_term).ratio()
        if score > best_score:
            best_score = score
            best_term = term

    if best_score < _MIN_SIMILARITY:
        return None
    return best_term


def _candidate_terms(postings: Iterable[PublicJobPosting]) -> set[str]:
    terms: set[str] = set()
    for posting in postings:
        if not _is_ongoing(posting):
            continue
        for source in [posting.title, *posting.ncs_categories]:
            for token in _TOKEN.findall(source or ""):
                if token not in _STOPWORDS:
                    terms.add(token)
    return terms


def _normalize_keyword(value: str) -> str:
    return re.sub(r"\s+", "", value.strip().lower())


def _is_ongoing(posting: PublicJobPosting) -> bool:
    return posting.is_ongoing is not False
