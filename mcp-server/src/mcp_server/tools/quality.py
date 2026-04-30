"""데이터 품질 검증 및 보정 도구.

등록 도구:
- validate_data_quality: 수집된 데이터의 품질을 검증
- detect_anomalies: 통계적 이상치 탐지
- check_data_completeness: 데이터 완전성 체크
- clean_data: 품질 검증 결과를 기반으로 데이터 자동 정제
- fix_data_issues: 발견된 이슈를 자동으로 수정

원칙:
- 결과는 §6 공통 스키마 준수
- 검증 규칙은 도메인별로 확장 가능
- 자동 수정은 보수적으로 (데이터 손실 최소화)
"""

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from mcp_server.observability.tracing import traced
from mcp_server.server import mcp


class ValidateDataQualityInput(BaseModel):
    """데이터 품질 검증 입력 스키마."""

    domain: str = Field(
        description="도메인 이름 (예: '부동산', '법률', '채용', '경매')",
        examples=["부동산", "법률"],
    )
    data_records: list[dict[str, Any]] = Field(
        description="검증할 데이터 레코드 리스트",
    )
    required_fields: list[str] | None = Field(
        default=None,
        description="필수 필드 목록 (미지정시 도메인별 기본값 사용)",
    )
    validation_rules: dict[str, str] | None = Field(
        default=None,
        description="필드별 검증 규칙 (예: {'price': 'positive_number', 'date': 'valid_date'})",
    )


class DetectAnomaliesInput(BaseModel):
    """이상치 탐지 입력 스키마."""

    domain: str = Field(
        description="도메인 이름",
    )
    data_records: list[dict[str, Any]] = Field(
        description="분석할 데이터 레코드 리스트",
    )
    target_field: str = Field(
        description="이상치를 탐지할 대상 필드명 (숫자형 필드)",
        examples=["price", "area", "count"],
    )
    threshold: float = Field(
        default=3.0,
        description="표준편차 기준 임계값 (기본 3.0 = 3-sigma)",
        ge=1.0,
        le=5.0,
    )


class CheckDataCompletenessInput(BaseModel):
    """데이터 완전성 체크 입력 스키마."""

    domain: str = Field(
        description="도메인 이름",
    )
    data_records: list[dict[str, Any]] = Field(
        description="체크할 데이터 레코드 리스트",
    )
    check_duplicates: bool = Field(
        default=True,
        description="중복 데이터 체크 여부",
    )
    check_nulls: bool = Field(
        default=True,
        description="NULL 값 체크 여부",
    )


@mcp.tool()
@traced("validate_data_quality")
async def validate_data_quality(input_data: ValidateDataQualityInput) -> dict[str, Any]:
    """데이터 품질을 검증합니다.
    
    수집된 데이터에 대해:
    1. 필수 필드 존재 여부 확인
    2. 필드 포맷 검증 (날짜, 숫자, 문자열 등)
    3. 데이터 유효성 검증
    
    Args:
        input_data: 도메인, 데이터 레코드, 필수 필드, 검증 규칙
        
    Returns:
        공통 스키마 dict (text, structured, source_url, metadata)
    """
    # 필수 필드 기본값 설정
    required_fields = input_data.required_fields or _get_default_required_fields(
        input_data.domain
    )
    
    # 검증 수행
    validation_results = []
    issues_count = 0
    
    for idx, record in enumerate(input_data.data_records):
        record_issues = []
        
        # 1. 필수 필드 체크
        missing_fields = [
            field for field in required_fields if field not in record or record[field] is None
        ]
        if missing_fields:
            record_issues.append({
                "type": "missing_fields",
                "severity": "error",
                "fields": missing_fields,
            })
        
        # 2. 필드 타입 및 포맷 검증
        if input_data.validation_rules:
            for field, rule in input_data.validation_rules.items():
                if field in record and record[field] is not None:
                    is_valid, error_msg = _validate_field(record[field], rule)
                    if not is_valid:
                        record_issues.append({
                            "type": "validation_failed",
                            "severity": "error",
                            "field": field,
                            "rule": rule,
                            "message": error_msg,
                        })
        
        if record_issues:
            issues_count += len(record_issues)
            validation_results.append({
                "record_index": idx,
                "issues": record_issues,
                "record_sample": _truncate_record(record),
            })
    
    # 통계 계산
    total_records = len(input_data.data_records)
    valid_records = total_records - len(validation_results)
    quality_score = (valid_records / total_records * 100) if total_records > 0 else 0
    
    # text 생성
    text_parts = [f"[{input_data.domain}] 데이터 품질 검증 결과"]
    text_parts.append(f"\n총 {total_records}개 레코드 중 {valid_records}개 정상")
    text_parts.append(f"품질 점수: {quality_score:.1f}%")
    
    if issues_count > 0:
        text_parts.append(f"\n⚠️ {issues_count}개 이슈 발견:")
        text_parts.append(f"  - 문제가 있는 레코드: {len(validation_results)}개")
    else:
        text_parts.append("\n✓ 모든 데이터가 품질 기준을 통과했습니다.")
    
    text = "\n".join(text_parts)
    
    return {
        "text": text,
        "structured": {
            "domain": input_data.domain,
            "total_records": total_records,
            "valid_records": valid_records,
            "invalid_records": len(validation_results),
            "quality_score": round(quality_score, 2),
            "issues_count": issues_count,
            "validation_results": validation_results[:10],  # 상위 10개만
            "validation_results_truncated": len(validation_results) > 10,
            "required_fields": required_fields,
            "checked_at": datetime.now().isoformat(),
        },
        "source_url": None,
        "metadata": {
            "fetched_at": datetime.now().isoformat(),
            "tool_name": "validate_data_quality",
            "domain": input_data.domain,
        },
    }


@mcp.tool()
@traced("detect_anomalies")
async def detect_anomalies(input_data: DetectAnomaliesInput) -> dict[str, Any]:
    """통계적 이상치를 탐지합니다.
    
    지정된 숫자 필드에 대해 평균과 표준편차를 계산하고,
    임계값을 벗어나는 이상치를 탐지합니다.
    
    Args:
        input_data: 도메인, 데이터 레코드, 대상 필드, 임계값
        
    Returns:
        공통 스키마 dict (text, structured, source_url, metadata)
    """
    # 대상 필드의 값 추출
    values = []
    valid_records = []
    
    for idx, record in enumerate(input_data.data_records):
        if input_data.target_field in record:
            value = record[input_data.target_field]
            if isinstance(value, (int, float)) and value is not None:
                values.append(value)
                valid_records.append((idx, record, value))
    
    if len(values) < 3:
        return {
            "text": f"⚠️ 이상치 탐지를 위한 데이터가 부족합니다 (최소 3개 필요, 현재 {len(values)}개)",
            "structured": {
                "domain": input_data.domain,
                "target_field": input_data.target_field,
                "error": "insufficient_data",
                "available_count": len(values),
            },
            "source_url": None,
            "metadata": {
                "fetched_at": datetime.now().isoformat(),
                "tool_name": "detect_anomalies",
            },
        }
    
    # 통계 계산
    mean = sum(values) / len(values)
    variance = sum((x - mean) ** 2 for x in values) / len(values)
    std_dev = variance ** 0.5
    
    # 이상치 탐지
    anomalies = []
    lower_bound = mean - (input_data.threshold * std_dev)
    upper_bound = mean + (input_data.threshold * std_dev)
    
    for idx, record, value in valid_records:
        if value < lower_bound or value > upper_bound:
            z_score = (value - mean) / std_dev if std_dev > 0 else 0
            anomalies.append({
                "record_index": idx,
                "value": value,
                "z_score": round(z_score, 2),
                "deviation_type": "low" if value < lower_bound else "high",
                "record_sample": _truncate_record(record),
            })
    
    # text 생성
    text_parts = [f"[{input_data.domain}] '{input_data.target_field}' 필드 이상치 분석"]
    text_parts.append(f"\n통계: 평균 {mean:.2f}, 표준편차 {std_dev:.2f}")
    text_parts.append(f"정상 범위: {lower_bound:.2f} ~ {upper_bound:.2f}")
    
    if anomalies:
        text_parts.append(f"\n⚠️ {len(anomalies)}개 이상치 발견:")
        for anomaly in anomalies[:3]:  # 상위 3개만 표시
            text_parts.append(
                f"  - 레코드 #{anomaly['record_index']}: "
                f"{anomaly['value']:.2f} (Z-score: {anomaly['z_score']})"
            )
        if len(anomalies) > 3:
            text_parts.append(f"  ... 외 {len(anomalies) - 3}개")
    else:
        text_parts.append("\n✓ 이상치가 발견되지 않았습니다.")
    
    text = "\n".join(text_parts)
    
    return {
        "text": text,
        "structured": {
            "domain": input_data.domain,
            "target_field": input_data.target_field,
            "statistics": {
                "count": len(values),
                "mean": round(mean, 2),
                "std_dev": round(std_dev, 2),
                "min": min(values),
                "max": max(values),
            },
            "threshold": input_data.threshold,
            "bounds": {
                "lower": round(lower_bound, 2),
                "upper": round(upper_bound, 2),
            },
            "anomalies_count": len(anomalies),
            "anomalies": anomalies[:20],  # 상위 20개
            "anomalies_truncated": len(anomalies) > 20,
            "analyzed_at": datetime.now().isoformat(),
        },
        "source_url": None,
        "metadata": {
            "fetched_at": datetime.now().isoformat(),
            "tool_name": "detect_anomalies",
            "domain": input_data.domain,
        },
    }


@mcp.tool()
@traced("check_data_completeness")
async def check_data_completeness(
    input_data: CheckDataCompletenessInput,
) -> dict[str, Any]:
    """데이터 완전성을 체크합니다.
    
    데이터에 대해:
    1. NULL/빈 값 체크
    2. 중복 데이터 탐지
    3. 데이터 커버리지 분석
    
    Args:
        input_data: 도메인, 데이터 레코드, 체크 옵션
        
    Returns:
        공통 스키마 dict (text, structured, source_url, metadata)
    """
    total_records = len(input_data.data_records)
    issues = []
    
    # 1. NULL 값 체크
    null_counts = {}
    if input_data.check_nulls and total_records > 0:
        # 모든 필드 수집
        all_fields = set()
        for record in input_data.data_records:
            all_fields.update(record.keys())
        
        # 각 필드별 NULL 개수 계산
        for field in all_fields:
            null_count = sum(
                1 for record in input_data.data_records
                if field not in record or record[field] is None or record[field] == ""
            )
            if null_count > 0:
                null_counts[field] = {
                    "null_count": null_count,
                    "null_rate": round(null_count / total_records * 100, 2),
                }
    
    # 2. 중복 데이터 체크
    duplicates = []
    if input_data.check_duplicates and total_records > 1:
        seen = {}
        for idx, record in enumerate(input_data.data_records):
            # 레코드를 정렬된 튜플로 변환 (해시 가능하게)
            record_key = tuple(sorted(record.items()))
            if record_key in seen:
                duplicates.append({
                    "original_index": seen[record_key],
                    "duplicate_index": idx,
                    "record_sample": _truncate_record(record),
                })
            else:
                seen[record_key] = idx
    
    # 완전성 점수 계산
    completeness_score = 100.0
    
    # NULL 비율에 따른 감점
    if null_counts:
        avg_null_rate = sum(info["null_rate"] for info in null_counts.values()) / len(null_counts)
        completeness_score -= avg_null_rate * 0.5
    
    # 중복 비율에 따른 감점
    if duplicates:
        duplicate_rate = len(duplicates) / total_records * 100
        completeness_score -= duplicate_rate
    
    completeness_score = max(0, completeness_score)
    
    # text 생성
    text_parts = [f"[{input_data.domain}] 데이터 완전성 분석 결과"]
    text_parts.append(f"\n총 {total_records}개 레코드 분석")
    text_parts.append(f"완전성 점수: {completeness_score:.1f}%")
    
    if null_counts:
        text_parts.append(f"\n⚠️ NULL 값 발견:")
        for field, info in list(null_counts.items())[:5]:
            text_parts.append(f"  - {field}: {info['null_count']}개 ({info['null_rate']}%)")
    
    if duplicates:
        text_parts.append(f"\n⚠️ 중복 데이터 {len(duplicates)}개 발견")
    
    if not null_counts and not duplicates:
        text_parts.append("\n✓ 데이터가 완전합니다.")
    
    text = "\n".join(text_parts)
    
    return {
        "text": text,
        "structured": {
            "domain": input_data.domain,
            "total_records": total_records,
            "completeness_score": round(completeness_score, 2),
            "null_analysis": null_counts,
            "duplicates_count": len(duplicates),
            "duplicates": duplicates[:10],  # 상위 10개
            "duplicates_truncated": len(duplicates) > 10,
            "checked_at": datetime.now().isoformat(),
        },
        "source_url": None,
        "metadata": {
            "fetched_at": datetime.now().isoformat(),
            "tool_name": "check_data_completeness",
            "domain": input_data.domain,
        },
    }


# ─────────────────────────────────────────────
# 헬퍼 함수
# ─────────────────────────────────────────────


def _get_default_required_fields(domain: str) -> list[str]:
    """도메인별 기본 필수 필드를 반환합니다."""
    defaults = {
        "부동산": ["deal_amount", "deal_year", "deal_month", "deal_day"],
        "법률": ["law_name", "law_id"],
        "채용": ["job_title", "company_name"],
        "경매": ["bid_title", "bid_no"],
    }
    return defaults.get(domain, [])


def _validate_field(value: Any, rule: str) -> tuple[bool, str | None]:
    """필드 값을 규칙에 따라 검증합니다."""
    if rule == "positive_number":
        if not isinstance(value, (int, float)) or value <= 0:
            return False, f"양수가 아님: {value}"
    
    elif rule == "valid_date":
        # 간단한 날짜 형식 체크 (YYYY-MM-DD 또는 YYYYMMDD)
        value_str = str(value)
        if not (len(value_str) in [8, 10] and value_str.replace("-", "").isdigit()):
            return False, f"잘못된 날짜 형식: {value}"
    
    elif rule == "not_empty":
        if not value or (isinstance(value, str) and not value.strip()):
            return False, "빈 값"
    
    elif rule == "numeric":
        if not isinstance(value, (int, float)):
            try:
                float(value)
            except (ValueError, TypeError):
                return False, f"숫자가 아님: {value}"
    
    return True, None


def _truncate_record(record: dict[str, Any], max_fields: int = 5) -> dict[str, Any]:
    """레코드를 일부 필드만 포함하도록 절단합니다."""
    if len(record) <= max_fields:
        return record
    
    items = list(record.items())[:max_fields]
    truncated = dict(items)
    truncated["..."] = f"({len(record) - max_fields} more fields)"
    return truncated


# ─────────────────────────────────────────────
# 데이터 정제 및 수정 도구
# ─────────────────────────────────────────────


class CleanDataInput(BaseModel):
    """데이터 정제 입력 스키마."""

    domain: str = Field(
        description="도메인 이름",
    )
    data_records: list[dict[str, Any]] = Field(
        description="정제할 데이터 레코드 리스트",
    )
    remove_duplicates: bool = Field(
        default=True,
        description="중복 제거 여부",
    )
    remove_nulls: bool = Field(
        default=False,
        description="NULL 값을 가진 레코드 완전 제거 여부 (주의: 데이터 손실)",
    )
    remove_anomalies: bool = Field(
        default=False,
        description="통계적 이상치 제거 여부",
    )
    anomaly_field: str | None = Field(
        default=None,
        description="이상치 탐지할 필드명 (remove_anomalies=true일 때 필수)",
    )
    anomaly_threshold: float = Field(
        default=3.0,
        description="이상치 임계값 (기본 3.0 sigma)",
    )


class FixDataIssuesInput(BaseModel):
    """데이터 이슈 수정 입력 스키마."""

    domain: str = Field(
        description="도메인 이름",
    )
    data_records: list[dict[str, Any]] = Field(
        description="수정할 데이터 레코드 리스트",
    )
    fill_missing_with_default: bool = Field(
        default=True,
        description="누락된 필드를 기본값으로 채울지 여부",
    )
    default_values: dict[str, Any] | None = Field(
        default=None,
        description="필드별 기본값 (예: {'status': 'unknown', 'count': 0})",
    )
    convert_types: bool = Field(
        default=True,
        description="타입 자동 변환 여부 (예: '123' → 123)",
    )
    trim_strings: bool = Field(
        default=True,
        description="문자열 공백 제거 여부",
    )


@mcp.tool()
@traced("clean_data")
async def clean_data(input_data: CleanDataInput) -> dict[str, Any]:
    """데이터를 자동으로 정제합니다.
    
    다음 작업을 수행합니다:
    1. 중복 데이터 제거
    2. NULL 값을 가진 레코드 제거 (선택)
    3. 통계적 이상치 제거 (선택)
    
    Args:
        input_data: 도메인, 데이터 레코드, 정제 옵션
        
    Returns:
        공통 스키마 dict (text, structured, source_url, metadata)
    """
    original_count = len(input_data.data_records)
    cleaned_records = input_data.data_records.copy()
    removed_records = []
    
    # 1. 중복 제거
    duplicates_removed = 0
    if input_data.remove_duplicates:
        seen = {}
        unique_records = []
        
        for idx, record in enumerate(cleaned_records):
            record_key = tuple(sorted(record.items()))
            if record_key not in seen:
                seen[record_key] = idx
                unique_records.append(record)
            else:
                duplicates_removed += 1
                removed_records.append({
                    "reason": "duplicate",
                    "index": idx,
                    "record": _truncate_record(record),
                })
        
        cleaned_records = unique_records
    
    # 2. NULL 값 제거
    nulls_removed = 0
    if input_data.remove_nulls:
        non_null_records = []
        
        for idx, record in enumerate(cleaned_records):
            has_null = any(
                value is None or value == ""
                for value in record.values()
            )
            
            if not has_null:
                non_null_records.append(record)
            else:
                nulls_removed += 1
                removed_records.append({
                    "reason": "contains_null",
                    "index": idx,
                    "record": _truncate_record(record),
                })
        
        cleaned_records = non_null_records
    
    # 3. 이상치 제거
    anomalies_removed = 0
    if input_data.remove_anomalies and input_data.anomaly_field:
        # 숫자 값 추출
        values = []
        valid_indices = []
        
        for idx, record in enumerate(cleaned_records):
            if input_data.anomaly_field in record:
                value = record[input_data.anomaly_field]
                if isinstance(value, (int, float)):
                    values.append(value)
                    valid_indices.append(idx)
        
        if len(values) >= 3:
            # 통계 계산
            mean = sum(values) / len(values)
            variance = sum((x - mean) ** 2 for x in values) / len(values)
            std_dev = variance ** 0.5
            
            lower_bound = mean - (input_data.anomaly_threshold * std_dev)
            upper_bound = mean + (input_data.anomaly_threshold * std_dev)
            
            # 이상치 제거
            non_anomaly_records = []
            
            for idx, record in enumerate(cleaned_records):
                if idx not in valid_indices:
                    non_anomaly_records.append(record)
                    continue
                
                value = record[input_data.anomaly_field]
                if lower_bound <= value <= upper_bound:
                    non_anomaly_records.append(record)
                else:
                    anomalies_removed += 1
                    removed_records.append({
                        "reason": "anomaly",
                        "index": idx,
                        "value": value,
                        "bounds": f"{lower_bound:.2f} ~ {upper_bound:.2f}",
                        "record": _truncate_record(record),
                    })
            
            cleaned_records = non_anomaly_records
    
    # 결과 통계
    cleaned_count = len(cleaned_records)
    total_removed = original_count - cleaned_count
    
    # text 생성
    text_parts = [f"[{input_data.domain}] 데이터 정제 완료"]
    text_parts.append(f"\n원본: {original_count}개 → 정제: {cleaned_count}개")
    
    if total_removed > 0:
        text_parts.append(f"제거된 레코드: {total_removed}개")
        if duplicates_removed > 0:
            text_parts.append(f"  - 중복: {duplicates_removed}개")
        if nulls_removed > 0:
            text_parts.append(f"  - NULL 포함: {nulls_removed}개")
        if anomalies_removed > 0:
            text_parts.append(f"  - 이상치: {anomalies_removed}개")
    else:
        text_parts.append("제거된 레코드 없음 (이미 깨끗함)")
    
    text = "\n".join(text_parts)
    
    return {
        "text": text,
        "structured": {
            "domain": input_data.domain,
            "original_count": original_count,
            "cleaned_count": cleaned_count,
            "removed_count": total_removed,
            "removal_summary": {
                "duplicates": duplicates_removed,
                "nulls": nulls_removed,
                "anomalies": anomalies_removed,
            },
            "cleaned_records": cleaned_records,
            "removed_records": removed_records[:20],  # 상위 20개만
            "removed_records_truncated": len(removed_records) > 20,
            "cleaned_at": datetime.now().isoformat(),
        },
        "source_url": None,
        "metadata": {
            "fetched_at": datetime.now().isoformat(),
            "tool_name": "clean_data",
            "domain": input_data.domain,
        },
    }


@mcp.tool()
@traced("fix_data_issues")
async def fix_data_issues(input_data: FixDataIssuesInput) -> dict[str, Any]:
    """발견된 데이터 이슈를 자동으로 수정합니다.
    
    다음 수정을 수행합니다:
    1. 누락된 필드를 기본값으로 채움
    2. 타입 자동 변환 (문자열 → 숫자 등)
    3. 문자열 공백 제거
    
    Args:
        input_data: 도메인, 데이터 레코드, 수정 옵션
        
    Returns:
        공통 스키마 dict (text, structured, source_url, metadata)
    """
    fixed_records = []
    fixes_applied = []
    total_fixes = 0
    
    # 기본값 설정
    default_values = input_data.default_values or _get_default_values(input_data.domain)
    
    for idx, record in enumerate(input_data.data_records):
        fixed_record = record.copy()
        record_fixes = []
        
        # 1. 누락된 필드 채우기
        if input_data.fill_missing_with_default:
            for field, default_value in default_values.items():
                if field not in fixed_record or fixed_record[field] is None:
                    fixed_record[field] = default_value
                    record_fixes.append({
                        "type": "fill_missing",
                        "field": field,
                        "value": default_value,
                    })
        
        # 2. 타입 변환
        if input_data.convert_types:
            for field, value in fixed_record.items():
                if isinstance(value, str):
                    # 숫자로 변환 시도
                    if value.isdigit():
                        fixed_record[field] = int(value)
                        record_fixes.append({
                            "type": "convert_type",
                            "field": field,
                            "from": "str",
                            "to": "int",
                        })
                    else:
                        try:
                            float_val = float(value)
                            fixed_record[field] = float_val
                            record_fixes.append({
                                "type": "convert_type",
                                "field": field,
                                "from": "str",
                                "to": "float",
                            })
                        except ValueError:
                            pass  # 변환 불가능한 문자열은 그대로 유지
        
        # 3. 문자열 공백 제거
        if input_data.trim_strings:
            for field, value in fixed_record.items():
                if isinstance(value, str):
                    trimmed = value.strip()
                    if trimmed != value:
                        fixed_record[field] = trimmed
                        record_fixes.append({
                            "type": "trim_string",
                            "field": field,
                        })
        
        fixed_records.append(fixed_record)
        
        if record_fixes:
            total_fixes += len(record_fixes)
            fixes_applied.append({
                "record_index": idx,
                "fixes": record_fixes,
                "original": _truncate_record(record),
                "fixed": _truncate_record(fixed_record),
            })
    
    # text 생성
    text_parts = [f"[{input_data.domain}] 데이터 이슈 수정 완료"]
    text_parts.append(f"\n총 {len(input_data.data_records)}개 레코드 처리")
    
    if total_fixes > 0:
        text_parts.append(f"적용된 수정: {total_fixes}개")
        text_parts.append(f"수정된 레코드: {len(fixes_applied)}개")
    else:
        text_parts.append("수정이 필요한 이슈 없음")
    
    text = "\n".join(text_parts)
    
    return {
        "text": text,
        "structured": {
            "domain": input_data.domain,
            "total_records": len(input_data.data_records),
            "fixed_records_count": len(fixes_applied),
            "total_fixes": total_fixes,
            "fixed_records": fixed_records,
            "fixes_applied": fixes_applied[:20],  # 상위 20개만
            "fixes_applied_truncated": len(fixes_applied) > 20,
            "fixed_at": datetime.now().isoformat(),
        },
        "source_url": None,
        "metadata": {
            "fetched_at": datetime.now().isoformat(),
            "tool_name": "fix_data_issues",
            "domain": input_data.domain,
        },
    }


def _get_default_values(domain: str) -> dict[str, Any]:
    """도메인별 기본값을 반환합니다."""
    defaults = {
        "부동산": {
            "status": "정상",
            "floor": 0,
        },
        "법률": {
            "status": "유효",
            "category": "기타",
        },
        "채용": {
            "status": "모집중",
            "salary": "미정",
        },
        "경매": {
            "status": "진행중",
            "estimate": 0,
        },
    }
    return defaults.get(domain, {})
