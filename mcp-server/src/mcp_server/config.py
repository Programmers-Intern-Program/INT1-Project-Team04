"""MCP 서버 환경 설정.

환경 변수 → pydantic-settings → 전역 settings 객체로 노출.
모든 모듈은 직접 os.environ 을 읽지 말고 이 settings 를 import 한다.
"""

from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

McpTransport = Literal["stdio", "sse"]


class Settings(BaseSettings):
    """애플리케이션 설정."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    pg_url: str = Field(
        description="MCP 내부 DB 접속 URL (asyncpg 드라이버). default 없음 — .env 미로드 시 ValidationError.",
    )

    langfuse_enabled: bool = Field(default=True)
    langfuse_host: str = Field(default="http://localhost:3000")
    langfuse_public_key: str = Field(default="")
    langfuse_secret_key: str = Field(default="")

    mcp_transport: McpTransport = Field(
        default="stdio",
        description="MCP transport: stdio (Inspector/로컬) 또는 sse (백엔드 연동). "
        "다른 값은 pydantic validation 단계에서 즉시 거부.",
    )
    mcp_sse_host: str = Field(default="0.0.0.0")
    mcp_sse_port: int = Field(default=8090)

    # 도메인별 외부 API 키 — 도메인 추가 시 여기 누적.
    # default="" 유지: 키 미발급 상태에서도 import/단위 테스트는 가능해야 함.
    # 실제 호출 직전 도구 레이어에서 빈 값이면 거부하는 책임.
    # data.go.kr 활용 신청은 자료유형 단위로 승인되므로 자료유형마다 변수 분리.
    # 실신청은 동일 서비스키여도 변수를 나눠두면 추후 키 교체·권한 분리에 유리.
    molit_apt_trade_api_key: str = Field(
        default="",
        description="국토교통부 아파트 매매 실거래가 (RTMSDataSvcAptTradeDev) 서비스 키.",
    )
    molit_apt_rent_api_key: str = Field(
        default="",
        description="국토교통부 아파트 전월세 실거래가 (RTMSDataSvcAptRent) 서비스 키.",
    )
    molit_offi_trade_api_key: str = Field(
        default="",
        description="국토교통부 오피스텔 매매 실거래가 (RTMSDataSvcOffiTrade) 서비스 키.",
    )
    molit_offi_rent_api_key: str = Field(
        default="",
        description="국토교통부 오피스텔 전월세 실거래가 (RTMSDataSvcOffiRent) 서비스 키.",
    )
    molit_rh_rent_api_key: str = Field(
        default="",
        description="국토교통부 연립다세대 전월세 실거래가 (RTMSDataSvcRHRent) 서비스 키.",
    )
    molit_rh_trade_api_key: str = Field(
        default="",
        description="국토교통부 연립다세대 매매 실거래가 (RTMSDataSvcRHTrade) 서비스 키.",
    )

    # ── 법률 도메인 ──
    moleg_law_info_api_key: str = Field(
        default="",
        description="법제처 국가법령정보 공유서비스 (data.go.kr/data/15000115) 서비스 키.",
    )
    na_bill_info_api_key: str = Field(
        default="",
        description="국회사무처 의안정보 통합 API (data.go.kr/data/15126134) 서비스 키.",
    )

    # ── 채용 도메인 ──
    moef_public_job_api_key: str = Field(
        default="",
        description="기획재정부 공공기관 채용정보 조회서비스 (data.go.kr/data/15125273) 서비스 키.",
    )
    keis_worknet_job_api_key: str = Field(
        default="",
        description="한국고용정보원 워크넷 채용정보 (data.go.kr/data/3038225) 서비스 키. "
        "현재 발급 키는 사용 불가 응답을 반환할 수 있음 — 도구 호출 경로 자체는 정상 동작.",
    )

    # ── 경매 도메인 ──
    pps_g2b_bid_api_key: str = Field(
        default="",
        description="조달청 나라장터 입찰공고정보서비스 (data.go.kr/data/15129394) 서비스 키.",
    )


@lru_cache
def get_settings() -> Settings:
    """싱글턴 settings. 테스트에서는 monkeypatch + cache_clear 로 초기화."""
    return Settings()
