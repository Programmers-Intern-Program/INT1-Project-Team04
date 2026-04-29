"""도구 입력 모델의 JSON Schema 를 stdout 으로 출력.

용도: Spring 측 Flyway 마이그레이션의 mcp_tool.input_schema 컬럼 값을 생성한다.
도구 추가 시 _TOOL_INPUT_MODELS 에 등록.

사용:
    uv run python scripts/export_tool_schema.py search_house_price
    uv run python scripts/export_tool_schema.py search_house_price > /tmp/schema.json
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any

from pydantic import BaseModel

from mcp_server.tools.real_estate import MolitRealEstateInput

# 부동산 5종 모두 region + deal_ymd 공통 입력 모델을 공유.
_TOOL_INPUT_MODELS: dict[str, type[BaseModel]] = {
    "search_house_price": MolitRealEstateInput,
    "search_apt_rent": MolitRealEstateInput,
    "search_offi_trade": MolitRealEstateInput,
    "search_offi_rent": MolitRealEstateInput,
    "search_rh_rent": MolitRealEstateInput,
}


def export_schema(tool_name: str) -> dict[str, Any]:
    """tool_name 의 입력 Pydantic 모델 → JSON Schema dict.

    Raises:
        KeyError: 등록되지 않은 도구.
    """
    try:
        model = _TOOL_INPUT_MODELS[tool_name]
    except KeyError as exc:
        known = ", ".join(sorted(_TOOL_INPUT_MODELS)) or "(없음)"
        raise KeyError(f"미등록 도구: {tool_name!r}. 등록된 도구: {known}") from exc
    return model.model_json_schema()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool_name", help="입력 스키마를 export 할 도구 이름")
    args = parser.parse_args(argv)

    try:
        schema = export_schema(args.tool_name)
    except KeyError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    json.dump(schema, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
