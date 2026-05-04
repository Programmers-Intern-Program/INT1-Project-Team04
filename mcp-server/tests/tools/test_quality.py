"""데이터 품질 검증 및 정제 도구 테스트."""

import pytest

from mcp_server.tools.quality import (
    CheckDataCompletenessInput,
    CleanDataInput,
    DetectAnomaliesInput,
    FixDataIssuesInput,
    ValidateDataQualityInput,
    check_data_completeness,
    clean_data,
    detect_anomalies,
    fix_data_issues,
    validate_data_quality,
)


@pytest.mark.asyncio
async def test_validate_data_quality_all_valid():
    """모든 데이터가 유효한 경우 테스트."""
    # Given
    data_records = [
        {"deal_amount": 50000, "deal_year": "2024", "deal_month": "03", "deal_day": "15"},
        {"deal_amount": 60000, "deal_year": "2024", "deal_month": "03", "deal_day": "16"},
    ]
    
    input_data = ValidateDataQualityInput(
        domain="부동산",
        data_records=data_records,
        validation_rules={"deal_amount": "positive_number"},
    )

    # When
    result = await validate_data_quality(input_data)

    # Then
    assert result["structured"]["quality_score"] == 100.0
    assert result["structured"]["valid_records"] == 2
    assert result["structured"]["invalid_records"] == 0
    assert result["structured"]["issues_count"] == 0


@pytest.mark.asyncio
async def test_validate_data_quality_missing_fields():
    """필수 필드가 누락된 경우 테스트."""
    # Given
    data_records = [
        {"deal_amount": 50000, "deal_year": "2024"},  # deal_month, deal_day 누락
        {"deal_amount": 60000, "deal_year": "2024", "deal_month": "03", "deal_day": "16"},
    ]
    
    input_data = ValidateDataQualityInput(
        domain="부동산",
        data_records=data_records,
    )

    # When
    result = await validate_data_quality(input_data)

    # Then
    assert result["structured"]["quality_score"] == 50.0
    assert result["structured"]["valid_records"] == 1
    assert result["structured"]["invalid_records"] == 1
    assert result["structured"]["issues_count"] > 0
    
    # 첫 번째 레코드에 문제가 있어야 함
    validation_results = result["structured"]["validation_results"]
    assert len(validation_results) == 1
    assert validation_results[0]["record_index"] == 0
    assert "missing_fields" in [issue["type"] for issue in validation_results[0]["issues"]]


@pytest.mark.asyncio
async def test_validate_data_quality_invalid_format():
    """잘못된 포맷의 데이터 테스트."""
    # Given
    data_records = [
        {"deal_amount": -1000, "deal_year": "2024"},  # 음수 금액
        {"deal_amount": "abc", "deal_year": "2024"},  # 문자열 금액
    ]
    
    input_data = ValidateDataQualityInput(
        domain="부동산",
        data_records=data_records,
        required_fields=["deal_amount"],
        validation_rules={"deal_amount": "positive_number"},
    )

    # When
    result = await validate_data_quality(input_data)

    # Then
    assert result["structured"]["quality_score"] == 0.0
    assert result["structured"]["invalid_records"] == 2


@pytest.mark.asyncio
async def test_detect_anomalies_normal_data():
    """이상치가 없는 정상 데이터 테스트."""
    # Given
    data_records = [
        {"price": 50000},
        {"price": 52000},
        {"price": 51000},
        {"price": 49000},
        {"price": 50500},
    ]
    
    input_data = DetectAnomaliesInput(
        domain="부동산",
        data_records=data_records,
        target_field="price",
        threshold=3.0,
    )

    # When
    result = await detect_anomalies(input_data)

    # Then
    assert result["structured"]["anomalies_count"] == 0
    assert "이상치가 발견되지 않았습니다" in result["text"]


@pytest.mark.asyncio
async def test_detect_anomalies_with_outliers():
    """이상치가 있는 데이터 테스트."""
    # Given
    data_records = [
        {"price": 50000},
        {"price": 51000},
        {"price": 49000},
        {"price": 50500},
        {"price": 100000},  # 이상치 (너무 높음)
        {"price": 10000},   # 이상치 (너무 낮음)
    ]
    
    input_data = DetectAnomaliesInput(
        domain="부동산",
        data_records=data_records,
        target_field="price",
        threshold=2.0,
    )

    # When
    result = await detect_anomalies(input_data)

    # Then
    assert result["structured"]["anomalies_count"] > 0
    assert "이상치 발견" in result["text"]
    
    # 통계 정보 확인
    stats = result["structured"]["statistics"]
    assert "mean" in stats
    assert "std_dev" in stats
    assert stats["count"] == 6


@pytest.mark.asyncio
async def test_detect_anomalies_insufficient_data():
    """데이터가 부족한 경우 테스트."""
    # Given
    data_records = [
        {"price": 50000},
        {"price": 51000},
    ]
    
    input_data = DetectAnomaliesInput(
        domain="부동산",
        data_records=data_records,
        target_field="price",
        threshold=3.0,
    )

    # When
    result = await detect_anomalies(input_data)

    # Then
    assert "부족합니다" in result["text"]
    assert result["structured"].get("error") == "insufficient_data"


@pytest.mark.asyncio
async def test_check_data_completeness_perfect():
    """완전한 데이터 테스트."""
    # Given
    data_records = [
        {"name": "A", "value": 100},
        {"name": "B", "value": 200},
        {"name": "C", "value": 300},
    ]
    
    input_data = CheckDataCompletenessInput(
        domain="법률",
        data_records=data_records,
    )

    # When
    result = await check_data_completeness(input_data)

    # Then
    assert result["structured"]["completeness_score"] == 100.0
    assert result["structured"]["duplicates_count"] == 0
    assert "완전합니다" in result["text"]


@pytest.mark.asyncio
async def test_check_data_completeness_with_nulls():
    """NULL 값이 있는 데이터 테스트."""
    # Given
    data_records = [
        {"name": "A", "value": 100},
        {"name": "B", "value": None},  # NULL
        {"name": None, "value": 300},  # NULL
        {"name": "D", "value": 400},
    ]
    
    input_data = CheckDataCompletenessInput(
        domain="법률",
        data_records=data_records,
        check_nulls=True,
    )

    # When
    result = await check_data_completeness(input_data)

    # Then
    assert result["structured"]["completeness_score"] < 100.0
    assert len(result["structured"]["null_analysis"]) > 0
    assert "NULL 값 발견" in result["text"]


@pytest.mark.asyncio
async def test_check_data_completeness_with_duplicates():
    """중복 데이터가 있는 경우 테스트."""
    # Given
    data_records = [
        {"name": "A", "value": 100},
        {"name": "B", "value": 200},
        {"name": "A", "value": 100},  # 중복
        {"name": "C", "value": 300},
    ]
    
    input_data = CheckDataCompletenessInput(
        domain="채용",
        data_records=data_records,
        check_duplicates=True,
    )

    # When
    result = await check_data_completeness(input_data)

    # Then
    assert result["structured"]["duplicates_count"] == 1
    assert "중복 데이터" in result["text"]


@pytest.mark.asyncio
async def test_validate_data_quality_common_schema():
    """공통 스키마 형식 준수 테스트."""
    # Given
    input_data = ValidateDataQualityInput(
        domain="경매",
        data_records=[{"bid_title": "테스트", "bid_no": "123"}],
    )

    # When
    result = await validate_data_quality(input_data)

    # Then - 공통 스키마 필드 검증
    assert "text" in result
    assert "structured" in result
    assert "source_url" in result
    assert "metadata" in result
    
    assert isinstance(result["text"], str)
    assert isinstance(result["structured"], dict)
    assert result["metadata"]["tool_name"] == "validate_data_quality"


@pytest.mark.asyncio
async def test_detect_anomalies_common_schema():
    """공통 스키마 형식 준수 테스트."""
    # Given
    input_data = DetectAnomaliesInput(
        domain="부동산",
        data_records=[{"price": 50000}, {"price": 51000}, {"price": 52000}],
        target_field="price",
    )

    # When
    result = await detect_anomalies(input_data)

    # Then
    assert "text" in result
    assert "structured" in result
    assert "source_url" in result
    assert "metadata" in result
    assert result["metadata"]["tool_name"] == "detect_anomalies"


@pytest.mark.asyncio
async def test_check_data_completeness_common_schema():
    """공통 스키마 형식 준수 테스트."""
    # Given
    input_data = CheckDataCompletenessInput(
        domain="법률",
        data_records=[{"law_name": "테스트법"}],
    )

    # When
    result = await check_data_completeness(input_data)

    # Then
    assert "text" in result
    assert "structured" in result
    assert "source_url" in result
    assert "metadata" in result
    assert result["metadata"]["tool_name"] == "check_data_completeness"


# ─────────────────────────────────────────────
# 데이터 정제 도구 테스트
# ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_clean_data_remove_duplicates():
    """중복 제거 테스트."""
    # Given
    data_records = [
        {"name": "A", "value": 100},
        {"name": "B", "value": 200},
        {"name": "A", "value": 100},  # 중복
        {"name": "C", "value": 300},
    ]
    
    input_data = CleanDataInput(
        domain="부동산",
        data_records=data_records,
        remove_duplicates=True,
    )

    # When
    result = await clean_data(input_data)

    # Then
    assert result["structured"]["original_count"] == 4
    assert result["structured"]["cleaned_count"] == 3
    assert result["structured"]["removal_summary"]["duplicates"] == 1


@pytest.mark.asyncio
async def test_clean_data_remove_nulls():
    """NULL 제거 테스트."""
    # Given
    data_records = [
        {"name": "A", "value": 100},
        {"name": "B", "value": None},  # NULL
        {"name": None, "value": 300},  # NULL
    ]
    
    input_data = CleanDataInput(
        domain="법률",
        data_records=data_records,
        remove_duplicates=False,
        remove_nulls=True,
    )

    # When
    result = await clean_data(input_data)

    # Then
    assert result["structured"]["cleaned_count"] == 1
    assert result["structured"]["removal_summary"]["nulls"] == 2


@pytest.mark.asyncio
async def test_clean_data_remove_anomalies():
    """이상치 제거 테스트."""
    # Given
    data_records = [
        {"price": 50000},
        {"price": 51000},
        {"price": 49000},
        {"price": 100000},  # 이상치
    ]
    
    input_data = CleanDataInput(
        domain="부동산",
        data_records=data_records,
        remove_duplicates=False,
        remove_nulls=False,
        remove_anomalies=True,
        anomaly_field="price",
        anomaly_threshold=2.0,
    )

    # When
    result = await clean_data(input_data)

    # Then
    assert result["structured"]["removal_summary"]["anomalies"] > 0


@pytest.mark.asyncio
async def test_fix_data_issues_fill_missing():
    """누락 필드 채우기 테스트."""
    # Given
    data_records = [
        {"name": "A"},  # value 누락
        {"name": "B", "value": 200},
    ]
    
    input_data = FixDataIssuesInput(
        domain="부동산",
        data_records=data_records,
        fill_missing_with_default=True,
        default_values={"value": 0},
    )

    # When
    result = await fix_data_issues(input_data)

    # Then
    fixed_records = result["structured"]["fixed_records"]
    assert "value" in fixed_records[0]
    assert fixed_records[0]["value"] == 0


@pytest.mark.asyncio
async def test_fix_data_issues_convert_types():
    """타입 변환 테스트."""
    # Given
    data_records = [
        {"name": "A", "value": "123"},  # 문자열 → 숫자
        {"name": "B", "value": "45.67"},  # 문자열 → float
    ]
    
    input_data = FixDataIssuesInput(
        domain="채용",
        data_records=data_records,
        convert_types=True,
    )

    # When
    result = await fix_data_issues(input_data)

    # Then
    fixed_records = result["structured"]["fixed_records"]
    assert isinstance(fixed_records[0]["value"], int)
    assert fixed_records[0]["value"] == 123
    assert isinstance(fixed_records[1]["value"], float)


@pytest.mark.asyncio
async def test_fix_data_issues_trim_strings():
    """문자열 공백 제거 테스트."""
    # Given
    data_records = [
        {"name": "  A  ", "value": 100},
        {"name": "B\t", "value": 200},
    ]
    
    input_data = FixDataIssuesInput(
        domain="법률",
        data_records=data_records,
        trim_strings=True,
    )

    # When
    result = await fix_data_issues(input_data)

    # Then
    fixed_records = result["structured"]["fixed_records"]
    assert fixed_records[0]["name"] == "A"
    assert fixed_records[1]["name"] == "B"


@pytest.mark.asyncio
async def test_clean_data_common_schema():
    """clean_data 공통 스키마 테스트."""
    # Given
    input_data = CleanDataInput(
        domain="경매",
        data_records=[{"bid_title": "테스트"}],
    )

    # When
    result = await clean_data(input_data)

    # Then
    assert "text" in result
    assert "structured" in result
    assert "source_url" in result
    assert "metadata" in result
    assert result["metadata"]["tool_name"] == "clean_data"


@pytest.mark.asyncio
async def test_fix_data_issues_common_schema():
    """fix_data_issues 공통 스키마 테스트."""
    # Given
    input_data = FixDataIssuesInput(
        domain="채용",
        data_records=[{"job_title": "개발자"}],
    )

    # When
    result = await fix_data_issues(input_data)

    # Then
    assert "text" in result
    assert "structured" in result
    assert "source_url" in result
    assert "metadata" in result
    assert result["metadata"]["tool_name"] == "fix_data_issues"
