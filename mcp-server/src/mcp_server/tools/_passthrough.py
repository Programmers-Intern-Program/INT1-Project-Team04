"""신규 도메인(법률/채용/경매) 도구 공통 헬퍼 — minimal raw passthrough.

수직 슬라이스 본작업 전 단계의 임시 구현.
정규화·통계 요약을 생략하고 외부 응답을 그대로 노출해 "Spring AI → MCP →
공공 API → 응답 반환" 경로를 검증하는 데 집중한다.

추후 각 도구를 정규화 버전으로 교체할 예정.
이 모듈은 그때까지만 유효한 임시 공통 코드 — 도메인별 normalizer 가 들어오면 삭제.
"""

from datetime import datetime
from typing import Any
from urllib.parse import urlencode

from mcp_server.sources import api_source_service

# tool_name → api_source.id 캐시 (real_estate.py 와 동일 패턴)
_cached_source_ids: dict[str, int] = {}

# 응답 본문 truncation. 토큰 폭증 방지
_RAW_TEXT_PREVIEW = 2000


async def resolve_source_id(tool_name: str) -> int:
    """tool_name → api_source.id. 첫 호출 시 lookup 후 dict 캐시."""
    if tool_name not in _cached_source_ids:
        _cached_source_ids[tool_name] = (
            await api_source_service.resolve_source_id_by_tool_name(tool_name)
        )
    return _cached_source_ids[tool_name]


def mask_query_string(params: dict[str, Any], *, secret_keys: tuple[str, ...]) -> str:
    """source_url 노출용 query string. secret_keys 에 해당하는 값은 마스킹.

    safe="*" 로 마스킹 문자가 percent-encode 되지 않도록 한다 (가독성).
    """
    masked = {k: ("***" if k in secret_keys else v) for k, v in params.items()}
    return urlencode(masked, safe="*")


def build_passthrough_response(
    *,
    raw,
    tool_name: str,
    source_id: int,
    params: dict[str, Any],
    secret_keys: tuple[str, ...],
    summary_text: str,
) -> dict[str, Any]:
    """공통 반환 스키마 빌더 (real_estate 도구와 동일한 4필드 구조).

    Args:
        raw: api_source_service.fetch() 결과 RawResult.
        tool_name: 도구명 (metadata 기록용).
        source_id: api_source.id (metadata 기록용).
        params: 호출 시 전송한 파라미터 dict (source_url 합성용).
        secret_keys: 마스킹 대상 파라미터 키 (서비스 키 등).
        summary_text: 사람이 읽는 한 줄 요약 (도메인 도구가 응답 길이/타입 등으로 합성).

    Returns:
        {text, structured, source_url, metadata} 공통 스키마.
        structured.raw 는 응답 본문을 _RAW_TEXT_PREVIEW 자로 절단한 미리보기.
        structured.raw_truncated 는 절단 여부 플래그.
    """
    raw_text = raw.content or ""
    truncated = len(raw_text) > _RAW_TEXT_PREVIEW
    preview = raw_text[:_RAW_TEXT_PREVIEW]

    url_template = raw.raw_metadata.get("url_template", "")
    source_url = (
        f"{url_template}?{mask_query_string(params, secret_keys=secret_keys)}"
        if url_template
        else None
    )

    return {
        "text": summary_text,
        "structured": {
            "raw": preview,
            "raw_truncated": truncated,
            "raw_length": len(raw_text),
            "query": {k: v for k, v in params.items() if k not in secret_keys},
        },
        "source_url": source_url,
        "metadata": {
            "fetched_at": (
                raw.fetched_at.isoformat()
                if isinstance(raw.fetched_at, datetime)
                else str(raw.fetched_at)
            ),
            "tool_name": tool_name,
            "source_id": source_id,
        },
    }


__all__ = [
    "build_passthrough_response",
    "mask_query_string",
    "resolve_source_id",
]
