"""채용 검색어 0건 검증/후보 추천 테스트."""

from datetime import date

from mcp_server.domains.jobs.normalizer import PublicJobPosting
from mcp_server.subscriptions.draft_models import NormalizedSubscriptionDraft
from mcp_server.subscriptions.recruitment_keyword_validation import (
    RecruitmentKeywordValidationResult,
    apply_recruitment_keyword_validation,
    suggest_recruitment_keyword,
)


def test_suggest_recruitment_keyword_from_similar_public_job_titles() -> None:
    postings = [
        _posting("백엔드 개발자 채용"),
        _posting("간호사 모집"),
        _posting("데이터 분석가 공개채용"),
    ]

    assert suggest_recruitment_keyword("벡엔드", postings) == "백엔드"
    assert suggest_recruitment_keyword("관호사", postings) == "간호사"


def test_apply_zero_result_suggestion_requires_keyword_confirmation() -> None:
    draft = NormalizedSubscriptionDraft(
        query="벡엔드 채용 공고",
        domain_name="recruitment",
        intent="job_posting_change",
        tool_name="search_public_job",
        parameters={
            "dataToolName": "search_public_job",
            "keyword": "벡엔드",
            "recrut_pbanc_ttl": "벡엔드",
            "conditionMetric": "COUNT",
            "conditionDirection": "UP",
            "conditionOperator": "GTE",
            "conditionThreshold": "1",
            "conditionUnit": "COUNT",
        },
        missing_fields=[],
        question="",
        confidence=0.9,
    )

    result = apply_recruitment_keyword_validation(
        draft,
        RecruitmentKeywordValidationResult(
            keyword="벡엔드",
            result_count=0,
            suggested_keyword="백엔드",
        ),
    )

    assert result.missing_fields == ["keywordConfirmation"]
    assert result.parameters["keyword"] == "벡엔드"
    assert result.parameters["keywordValidationStatus"] == "ZERO_RESULTS"
    assert result.parameters["suggestedKeyword"] == "백엔드"
    assert "벡엔드" in result.question
    assert "백엔드" in result.question


def _posting(title: str) -> PublicJobPosting:
    return PublicJobPosting(
        pblnt_sn=1,
        title=title,
        institute="테스트기관",
        pbanc_begin_date=date(2026, 5, 1),
        pbanc_end_date=date(2026, 5, 31),
        is_ongoing=True,
    )
