"""부동산 도메인 전용 예외.

sources.errors.SourceError 와 별개. 외부 호출은 성공했지만 도메인 의미로
해석 불가능한 응답(정규화 실패) 또는 도메인 입력/설정 자체의 문제를 분리해 다룬다.
"""


class RealEstateError(Exception):
    """부동산 도메인의 모든 예외 베이스."""


class RealEstateConfigError(RealEstateError):
    """도메인 호출에 필요한 설정 누락 (예: MOLIT_TRADE_API_KEY 미설정).

    SourceFetchError 와 구분되는 이유: 호출 자체가 시도되지 않음. 재시도 의미 없음.
    운영자가 환경변수를 채울 때까지 영원히 실패.
    """


class RealEstateRegionNotFoundError(RealEstateError):
    """자연어 region 입력이 LAWD_CD 로 변환 불가.

    - candidates 가 비어있으면 매칭 0건 (오타 등)
    - candidates 가 2건 이상이면 동음이의 (예: "중구") — 호출자가 "서울 중구" 처럼 시도와 함께 재입력해야 함

    candidates 형식: [(표시명, 5자리 코드), ...] — AI 가 메시지에서 후보를 읽고 재선택하도록 한다.
    """

    def __init__(self, message: str, candidates: list[tuple[str, str]] | None = None) -> None:
        super().__init__(message)
        self.candidates: list[tuple[str, str]] = candidates or []


class RealEstateNormalizationError(RealEstateError):
    """MOLIT 응답 정규화 실패.

    - resultCode != "000" (API 가 에러 코드 반환)
    - XML 자체가 깨져 파싱 불가
    - 필수 필드 누락
    """


__all__ = [
    "RealEstateError",
    "RealEstateConfigError",
    "RealEstateRegionNotFoundError",
    "RealEstateNormalizationError",
]
