"""법률 도메인 전용 예외."""


class LawError(Exception):
    """법률 도메인의 모든 예외 베이스."""


class LawConfigError(LawError):
    """도메인 호출에 필요한 설정 누락 (서비스 키 미설정).

    SourceFetchError 와 구분되는 이유: 호출 자체가 시도되지 않음. 재시도 무의미.
    """


class LawNormalizationError(LawError):
    """API 응답 본문 정규화 실패 (XML 파싱, 응답 코드 비정상, 필수 필드 누락).

    sources 계층의 SourceError 와 분리: 외부 호출은 성공했으나 본문이 도메인
    스키마와 맞지 않는 상황.
    """


__all__ = ["LawConfigError", "LawError", "LawNormalizationError"]
