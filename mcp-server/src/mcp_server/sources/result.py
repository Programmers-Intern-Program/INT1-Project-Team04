"""외부 호출 공통 반환 타입.

api_source / crawl_source 어떤 경로로 가져왔든 도구 입장에서는 동일한 형태로 보임.
"""

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

SourceType = Literal["api", "crawl"]


class RawResult(BaseModel):
    """외부 데이터 소스 호출 1회 결과.

    도구는 이 결과를 받아 도메인 정규화 (`domains/<domain>/normalizer.py`) 를 거친 뒤
    Tool 반환 공통 스키마 로 변환해 백엔드에 전달한다.
    """

    source_type: SourceType
    source_id: int
    content: str = Field(description="원문 텍스트 또는 JSON 직렬화 문자열")
    fetched_at: datetime
    raw_metadata: dict[str, Any] = Field(default_factory=dict)
