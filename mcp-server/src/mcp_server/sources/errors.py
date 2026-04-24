"""sources 레이어 전용 예외."""


class SourceError(Exception):
    """sources 레이어의 모든 예외 베이스."""


class SourceNotFoundError(SourceError):
    """등록되지 않은 source_id 호출."""


class SourceFetchError(SourceError):
    """외부 호출 실패 (네트워크/HTTP/렌더링).

    호출 자체는 시도되었으나 실패 — 재시도·백오프 정책의 대상이 될 수 있음.
    """


class SourceNotImplementedError(SourceError):
    """해당 경로의 호출 구현이 아직 없음 (예: Phase 3-3 이전 `crawl_source._render`).

    SourceFetchError 와 구분되는 이유: 재시도 의미 없음. 구현 완료될 때까지 영원히 실패.
    도구 레이어는 이 예외를 catch 해서 재시도 로직에 넣지 말 것.
    """
