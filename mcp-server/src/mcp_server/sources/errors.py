"""sources 레이어 전용 예외."""


class SourceError(Exception):
    """sources 레이어의 모든 예외 베이스."""


class SourceNotFoundError(SourceError):
    """등록되지 않은 source_id 호출."""


class SourceFetchError(SourceError):
    """외부 호출 실패 (네트워크/HTTP/렌더링)."""
