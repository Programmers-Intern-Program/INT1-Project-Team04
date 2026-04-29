"""법률 도메인 전용 예외."""


class LawError(Exception):
    """법률 도메인의 모든 예외 베이스."""


class LawConfigError(LawError):
    """도메인 호출에 필요한 설정 누락 (서비스 키 미설정).

    SourceFetchError 와 구분되는 이유: 호출 자체가 시도되지 않음. 재시도 무의미.
    """


__all__ = ["LawError", "LawConfigError"]
