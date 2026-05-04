"""채용 도메인 전용 예외."""


class JobsError(Exception):
    """채용 도메인의 모든 예외 베이스."""


class JobsConfigError(JobsError):
    """도메인 호출에 필요한 설정 누락 (서비스 키 미설정).

    SourceFetchError 와 구분되는 이유: 호출 자체가 시도되지 않음. 재시도 무의미.
    """


class JobsNormalizationError(JobsError):
    """API 응답 본문 정규화 실패 (XML/JSON 파싱, 응답 코드 비정상, 필수 필드 누락)."""


class WorknetPermissionDeniedError(JobsNormalizationError):
    """워크넷 OPEN-API 가 개인회원 키에 대해 거부 응답 반환.

    응답 본문: <error>개인회원은 사용할 수 없는 OPEN-API입니다.</error>
    키 교체로 해결 불가 (사업자/기관 회원 한정). 도구 레이어가 잡아서
    사용자에게 명확한 "권한 미발급" 안내 응답을 반환할 수 있도록 일반
    정규화 실패와 분리한다.
    """


__all__ = [
    "JobsConfigError",
    "JobsError",
    "JobsNormalizationError",
    "WorknetPermissionDeniedError",
]
