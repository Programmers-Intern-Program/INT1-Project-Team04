# 데이터 품질 검증 도구 가이드

## 개요

데이터 품질 검증 도구는 MCP 서버에서 수집한 데이터의 품질을 자동으로 검증하고 개선하는 3가지 도구를 제공합니다.

## 도구 목록

### 1. `validate_data_quality` - 데이터 품질 검증

수집된 데이터의 전반적인 품질을 검증합니다.

**검증 항목:**
- 필수 필드 존재 여부
- 필드 포맷 검증 (날짜, 숫자, 문자열)
- 데이터 유효성 검증
- 품질 점수 계산

**입력 파라미터:**

```json
{
  "domain": "부동산",
  "data_records": [
    {
      "deal_amount": 50000,
      "deal_year": "2024",
      "deal_month": "03",
      "deal_day": "15"
    }
  ],
  "required_fields": ["deal_amount", "deal_year"],
  "validation_rules": {
    "deal_amount": "positive_number",
    "deal_year": "not_empty"
  }
}
```

**검증 규칙:**
- `positive_number`: 양수 여부
- `valid_date`: 날짜 형식 (YYYY-MM-DD 또는 YYYYMMDD)
- `not_empty`: 빈 값 아님
- `numeric`: 숫자형

**출력 예시:**

```json
{
  "text": "[부동산] 데이터 품질 검증 결과\n총 100개 레코드 중 95개 정상\n품질 점수: 95.0%",
  "structured": {
    "domain": "부동산",
    "total_records": 100,
    "valid_records": 95,
    "invalid_records": 5,
    "quality_score": 95.0,
    "issues_count": 7,
    "validation_results": [...]
  }
}
```

### 2. `detect_anomalies` - 이상치 탐지

통계적 방법으로 데이터의 이상치를 탐지합니다.

**탐지 방법:**
- 평균과 표준편차 계산
- Z-score 기반 이상치 판별
- 설정 가능한 임계값 (기본 3-sigma)

**입력 파라미터:**

```json
{
  "domain": "부동산",
  "data_records": [
    {"price": 50000},
    {"price": 51000},
    {"price": 100000}
  ],
  "target_field": "price",
  "threshold": 3.0
}
```

**출력 예시:**

```json
{
  "text": "[부동산] 'price' 필드 이상치 분석\n통계: 평균 67000.00, 표준편차 28868.00\n정상 범위: -19604.00 ~ 153604.00\n⚠️ 0개 이상치 발견",
  "structured": {
    "statistics": {
      "count": 3,
      "mean": 67000.0,
      "std_dev": 28868.0,
      "min": 50000,
      "max": 100000
    },
    "anomalies_count": 0,
    "anomalies": []
  }
}
```

### 3. `check_data_completeness` - 데이터 완전성 체크

데이터의 완전성을 분석합니다.

**체크 항목:**
- NULL/빈 값 분석
- 중복 데이터 탐지
- 필드별 NULL 비율 계산
- 완전성 점수 계산

**입력 파라미터:**

```json
{
  "domain": "법률",
  "data_records": [
    {"law_name": "A법", "law_id": "001"},
    {"law_name": "B법", "law_id": null},
    {"law_name": "A법", "law_id": "001"}
  ],
  "check_duplicates": true,
  "check_nulls": true
}
```

**출력 예시:**

```json
{
  "text": "[법률] 데이터 완전성 분석 결과\n총 3개 레코드 분석\n완전성 점수: 83.3%\n⚠️ NULL 값 발견:\n  - law_id: 1개 (33.33%)\n⚠️ 중복 데이터 1개 발견",
  "structured": {
    "completeness_score": 83.3,
    "null_analysis": {
      "law_id": {
        "null_count": 1,
        "null_rate": 33.33
      }
    },
    "duplicates_count": 1,
    "duplicates": [...]
  }
}
```

## 사용 시나리오

### 시나리오 1: 부동산 데이터 품질 검증

```python
# 1. 데이터 수집
apt_data = await search_house_price({"region": "강남구", "deal_ymd": "202403"})

# 2. 품질 검증
quality_result = await validate_data_quality({
    "domain": "부동산",
    "data_records": apt_data["structured"]["trades"],
    "validation_rules": {
        "deal_amount": "positive_number",
        "deal_year": "valid_date"
    }
})

# 3. 품질 점수 확인
if quality_result["structured"]["quality_score"] < 90:
    print("⚠️ 데이터 품질이 낮습니다. 재수집 필요")
```

### 시나리오 2: 이상치 탐지 및 제거

```python
# 1. 데이터 수집
data = await fetch_law_data()

# 2. 이상치 탐지
anomaly_result = await detect_anomalies({
    "domain": "법률",
    "data_records": data["structured"]["records"],
    "target_field": "view_count",
    "threshold": 2.5
})

# 3. 이상치 제거
if anomaly_result["structured"]["anomalies_count"] > 0:
    anomaly_indices = [a["record_index"] for a in anomaly_result["structured"]["anomalies"]]
    cleaned_data = [r for i, r in enumerate(data) if i not in anomaly_indices]
```

### 시나리오 3: 완전성 체크 및 보완

```python
# 1. 완전성 체크
completeness = await check_data_completeness({
    "domain": "채용",
    "data_records": job_data,
    "check_duplicates": True,
    "check_nulls": True
})

# 2. NULL 필드 보완
null_fields = completeness["structured"]["null_analysis"]
for field, info in null_fields.items():
    if info["null_rate"] > 20:  # 20% 이상 NULL
        print(f"⚠️ {field} 필드의 NULL 비율이 높습니다: {info['null_rate']}%")
        # 재수집 또는 기본값 설정

# 3. 중복 제거
if completeness["structured"]["duplicates_count"] > 0:
    print(f"중복 {completeness['structured']['duplicates_count']}개 제거 필요")
```

## 도메인별 기본 필수 필드

각 도메인별로 자동으로 적용되는 기본 필수 필드:

| 도메인 | 기본 필수 필드 |
|--------|----------------|
| 부동산 | `deal_amount`, `deal_year`, `deal_month`, `deal_day` |
| 법률 | `law_name`, `law_id` |
| 채용 | `job_title`, `company_name` |
| 경매 | `bid_title`, `bid_no` |

커스텀 필드를 지정하려면 `required_fields` 파라미터를 사용하세요.

## 통합 예시: 전체 파이프라인

```python
async def quality_assured_data_pipeline(domain: str, query: dict):
    """품질이 보장된 데이터 파이프라인."""
    
    # 1. 데이터 수집
    raw_data = await fetch_domain_data(domain, query)
    records = raw_data["structured"]["records"]
    
    # 2. 품질 검증
    quality = await validate_data_quality({
        "domain": domain,
        "data_records": records
    })
    
    if quality["structured"]["quality_score"] < 80:
        raise ValueError(f"데이터 품질 부족: {quality['structured']['quality_score']}%")
    
    # 3. 완전성 체크
    completeness = await check_data_completeness({
        "domain": domain,
        "data_records": records
    })
    
    # 중복 제거
    if completeness["structured"]["duplicates_count"] > 0:
        # ... 중복 제거 로직
        pass
    
    # 4. 이상치 탐지 (숫자 필드가 있는 경우)
    if "price" in records[0]:
        anomalies = await detect_anomalies({
            "domain": domain,
            "data_records": records,
            "target_field": "price"
        })
        
        # 이상치 제거
        if anomalies["structured"]["anomalies_count"] > 0:
            # ... 이상치 제거 로직
            pass
    
    # 5. 정제된 데이터 반환
    return {
        "data": records,
        "quality_score": quality["structured"]["quality_score"],
        "completeness_score": completeness["structured"]["completeness_score"]
    }
```

## 백엔드 연동 예시

Spring Boot 백엔드에서 사용하는 경우:

```java
// ScheduleExecutionService.java
@Service
public class ScheduleExecutionService {
    
    @Autowired
    private McpHttpAdapter mcpAdapter;
    
    public void executeSchedule(Schedule schedule) {
        // 1. 데이터 수집
        McpExecutionResult rawData = mcpAdapter.callTool(
            "search_house_price",
            Map.of("region", "강남구", "deal_ymd", "202403")
        );
        
        // 2. 품질 검증
        McpExecutionResult qualityCheck = mcpAdapter.callTool(
            "validate_data_quality",
            Map.of(
                "domain", "부동산",
                "data_records", extractRecords(rawData)
            )
        );
        
        double qualityScore = extractQualityScore(qualityCheck);
        
        if (qualityScore < 80.0) {
            log.warn("데이터 품질이 낮습니다: {}%", qualityScore);
            // 재수집 또는 알림
            return;
        }
        
        // 3. 정상 데이터 처리
        processData(rawData);
    }
}
```

## 테스트

```bash
# 품질 검증 도구 테스트 실행
uv run pytest tests/tools/test_quality.py -v

# 특정 테스트만 실행
uv run pytest tests/tools/test_quality.py::test_validate_data_quality_all_valid -v
```

## 주의사항

1. **대용량 데이터**: `data_records`가 1000개 이상인 경우 처리 시간이 길어질 수 있습니다.
2. **메모리**: 이상치 탐지는 모든 데이터를 메모리에 로드하므로 적절한 청크 크기로 나눠 처리하세요.
3. **도메인별 커스터마이징**: 도메인별로 다른 검증 규칙이 필요한 경우 `validation_rules`를 활용하세요.

## 향후 개선 사항

- [ ] 더 많은 검증 규칙 추가 (email, url, phone 등)
- [ ] 머신러닝 기반 이상치 탐지
- [ ] 데이터 품질 트렌드 추적
- [ ] 자동 수정 제안 기능
