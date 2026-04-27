"""scripts.export_tool_schema 단위 테스트."""

from __future__ import annotations

import json

import pytest

from scripts.export_tool_schema import export_schema, main


def test_export_schema_returns_dict_with_required_fields_for_search_house_price():
    schema = export_schema("search_house_price")
    assert schema["type"] == "object"
    assert set(schema["required"]) == {"region", "deal_ymd"}
    assert "region" in schema["properties"]
    assert "deal_ymd" in schema["properties"]


def test_export_schema_raises_for_unknown_tool():
    with pytest.raises(KeyError) as excinfo:
        export_schema("nope_not_real")
    assert "search_house_price" in str(excinfo.value)


def test_main_writes_pretty_json_to_stdout(capsys):
    rc = main(["search_house_price"])
    captured = capsys.readouterr()
    assert rc == 0
    assert captured.err == ""
    parsed = json.loads(captured.out)
    assert parsed["properties"]["region"]["type"] == "string"
    assert parsed["properties"]["deal_ymd"]["type"] == "string"


def test_main_returns_nonzero_for_unknown_tool(capsys):
    rc = main(["nope_not_real"])
    captured = capsys.readouterr()
    assert rc == 2
    assert "미등록 도구" in captured.err
