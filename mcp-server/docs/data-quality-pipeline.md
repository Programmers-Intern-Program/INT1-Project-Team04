# 데이터 품질 관리 파이프라인 가이드

## 개요

데이터 수집부터 정제, 검증, 수정까지 전체 품질 관리 파이프라인을 안내합니다.

---

## 📊 전체 파이프라인 흐름

```
┌─────────────────┐
│  1. 데이터 수집  │  (기존 도구: search_house_price, search_law 등)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  2. 품질 검증    │  validate_data_quality
│   - 필수 필드    │  ├─ 품질 점수 < 80? → 재수집
│   - 포맷 검증    │  └─ 품질 점수 ≥ 80? → 다음 단계
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  3. 완전성 체크  │  check_data_completeness
│   - NULL 분석    │  ├─ NULL 비율 > 20%? → 재수집
│   - 중복 탐지    │  └─ 정상? → 다음 단계
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  4. 이상치 탐지  │  detect_anomalies
│   - 통계적 분석  │  └─ 이상치 발견? → 정제 필요
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  5. 데이터 정제  │  clean_data
│   - 중복 제거    │  ├─ 중복 제거
│   - NULL 제거    │  ├─ NULL 레코드 제거 (선택)
│   - 이상치 제거  │  └─ 이상치 제거 (선택)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  6. 이슈 수정    │  fix_data_issues
│   - 기본값 채움  │  ├─ 누락 필드 → 기본값
│   - 타입 변환    │  ├─ "123" → 123
│   - 공백 제거    │  └─ " text " → "text"
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  7. 최종 저장    │  (백엔드 DB 저장 / AI 분석)
└─────────────────┘
```

---

## 🔄 3가지 처리 전략

### 전략 1: **보수적 정제** (기본 권장)

데이터 손실을 최소화하면서 품질을 개선합니다.

```python
async def conservative_pipeline(domain: str, raw_data: list[dict]):
    """보수적 정제 - 데이터 손실 최소화"""
    
    # 1. 품질 검증
    validation = await validate_data_quality({
        "domain": domain,
        "data_records": raw_data
    })
    
    if validation["structured"]["quality_score"] < 60:
        raise ValueError("품질이 너무 낮음 - 재수집 필요")
    
    # 2. 완전성 체크
    completeness = await check_data_completeness({
        "domain": domain,
        "data_records": raw_data,
        "check_duplicates": True,
        "check_nulls": True
    })
    
    # 3. 이슈 수정 (데이터 제거 없이 수정만)
    fixed = await fix_data_issues({
        "domain": domain,
        "data_records": raw_data,
        "fill_missing_with_default": True,
        "convert_types": True,
        "trim_strings": True
    })
    
    # 4. 중복만 제거 (NULL/이상치는 유지)
    cleaned = await clean_data({
        "domain": domain,
        "data_records": fixed["structured"]["fixed_records"],
        "remove_duplicates": True,
        "remove_nulls": False,  # 보수적: NULL 유지
        "remove_anomalies": False  # 보수적: 이상치 유지
    })
    
    return cleaned["structured"]["cleaned_records"]
```

**언제 사용:**
- 데이터가 희소한 경우 (법률, 경매)
- 모든 데이터가 중요한 경우
- NULL이나 이상치에도 의미가 있는 경우

---

### 전략 2: **적극적 정제** (고품질 우선)

품질이 낮은 데이터는 과감하게 제거합니다.

```python
async def aggressive_pipeline(domain: str, raw_data: list[dict]):
    """적극적 정제 - 고품질 데이터만 유지"""
    
    # 1. 품질 검증
    validation = await validate_data_quality({
        "domain": domain,
        "data_records": raw_data
    })
    
    # 품질이 낮으면 즉시 실패
    if validation["structured"]["quality_score"] < 80:
        raise ValueError(f"품질 부족: {validation['structured']['quality_score']}%")
    
    # 2. 이상치 탐지
    anomalies = await detect_anomalies({
        "domain": domain,
        "data_records": raw_data,
        "target_field": "deal_amount",  # 도메인별로 조정
        "threshold": 2.5  # 더 엄격한 기준
    })
    
    # 3. 적극적 정제
    cleaned = await clean_data({
        "domain": domain,
        "data_records": raw_data,
        "remove_duplicates": True,
        "remove_nulls": True,  # 적극적: NULL 제거
        "remove_anomalies": True,  # 적극적: 이상치 제거
        "anomaly_field": "deal_amount",
        "anomaly_threshold": 2.5
    })
    
    # 4. 남은 데이터 수정
    fixed = await fix_data_issues({
        "domain": domain,
        "data_records": cleaned["structured"]["cleaned_records"],
        "fill_missing_with_default": True,
        "convert_types": True,
        "trim_strings": True
    })
    
    return fixed["structured"]["fixed_records"]
```

**언제 사용:**
- 데이터가 풍부한 경우 (부동산)
- 품질이 중요한 경우 (AI 학습 데이터)
- 노이즈를 제거하고 싶은 경우

---

### 전략 3: **단계적 정제** (균형)

검증 → 수정 → 재검증 → 정제의 순환 구조입니다.

```python
async def iterative_pipeline(domain: str, raw_data: list[dict], max_iterations: int = 3):
    """단계적 정제 - 균형잡힌 접근"""
    
    current_data = raw_data
    
    for iteration in range(max_iterations):
        # 1. 현재 품질 측정
        validation = await validate_data_quality({
            "domain": domain,
            "data_records": current_data
        })
        
        quality_score = validation["structured"]["quality_score"]
        print(f"반복 {iteration + 1}: 품질 점수 {quality_score}%")
        
        # 품질이 충분하면 종료
        if quality_score >= 90:
            break
        
        # 2. 이슈 수정
        fixed = await fix_data_issues({
            "domain": domain,
            "data_records": current_data,
            "fill_missing_with_default": True,
            "convert_types": True,
            "trim_strings": True
        })
        
        current_data = fixed["structured"]["fixed_records"]
        
        # 3. 점진적 정제
        cleaned = await clean_data({
            "domain": domain,
            "data_records": current_data,
            "remove_duplicates": True,
            "remove_nulls": False,  # 첫 반복에서는 유지
            "remove_anomalies": iteration > 0  # 두 번째 반복부터 이상치 제거
        })
        
        current_data = cleaned["structured"]["cleaned_records"]
    
    return current_data
```

**언제 사용:**
- 품질이 불확실한 경우
- 다양한 소스의 데이터를 통합하는 경우
- 점진적으로 품질을 개선하고 싶은 경우

---

## 💡 실전 예시

### 예시 1: 부동산 데이터 수집 → 정제 파이프라인

```python
async def collect_and_clean_apt_data(region: str, deal_ymd: str):
    """부동산 데이터 수집 및 정제"""
    
    # ===== 1단계: 데이터 수집 =====
    raw_result = await search_house_price({
        "region": region,
        "deal_ymd": deal_ymd
    })
    
    raw_trades = raw_result["structured"]["trades"]
    print(f"✓ 수집: {len(raw_trades)}개 레코드")
    
    # ===== 2단계: 품질 검증 =====
    validation = await validate_data_quality({
        "domain": "부동산",
        "data_records": raw_trades,
        "validation_rules": {
            "deal_amount": "positive_number",
            "area": "positive_number"
        }
    })
    
    quality_score = validation["structured"]["quality_score"]
    print(f"✓ 품질 점수: {quality_score}%")
    
    if quality_score < 70:
        print("⚠️ 품질이 낮습니다. 재수집을 권장합니다.")
        return None
    
    # ===== 3단계: 완전성 체크 =====
    completeness = await check_data_completeness({
        "domain": "부동산",
        "data_records": raw_trades,
        "check_duplicates": True,
        "check_nulls": True
    })
    
    print(f"✓ 완전성: {completeness['structured']['completeness_score']}%")
    
    # ===== 4단계: 이상치 탐지 =====
    anomalies = await detect_anomalies({
        "domain": "부동산",
        "data_records": raw_trades,
        "target_field": "deal_amount",
        "threshold": 3.0
    })
    
    anomaly_count = anomalies["structured"]["anomalies_count"]
    print(f"✓ 이상치: {anomaly_count}개 발견")
    
    # ===== 5단계: 데이터 정제 =====
    cleaned = await clean_data({
        "domain": "부동산",
        "data_records": raw_trades,
        "remove_duplicates": True,
        "remove_nulls": False,
        "remove_anomalies": anomaly_count > 5,  # 이상치가 많으면 제거
        "anomaly_field": "deal_amount" if anomaly_count > 5 else None
    })
    
    cleaned_trades = cleaned["structured"]["cleaned_records"]
    print(f"✓ 정제 완료: {len(cleaned_trades)}개 레코드")
    
    # ===== 6단계: 이슈 수정 =====
    fixed = await fix_data_issues({
        "domain": "부동산",
        "data_records": cleaned_trades,
        "fill_missing_with_default": True,
        "default_values": {
            "floor": 1,
            "build_year": 2000
        },
        "convert_types": True,
        "trim_strings": True
    })
    
    final_trades = fixed["structured"]["fixed_records"]
    print(f"✓ 최종: {len(final_trades)}개 레코드 (품질 보장)")
    
    return {
        "trades": final_trades,
        "quality_score": quality_score,
        "completeness_score": completeness["structured"]["completeness_score"],
        "original_count": len(raw_trades),
        "final_count": len(final_trades),
        "removed_count": len(raw_trades) - len(final_trades)
    }
```

### 예시 2: 법률 데이터 품질 개선

```python
async def improve_law_data_quality(law_data: list[dict]):
    """법률 데이터 품질 개선 (보수적 접근)"""
    
    print(f"원본 데이터: {len(law_data)}개")
    
    # 1. 현재 상태 분석
    validation = await validate_data_quality({
        "domain": "법률",
        "data_records": law_data
    })
    
    completeness = await check_data_completeness({
        "domain": "법률",
        "data_records": law_data,
        "check_duplicates": True,
        "check_nulls": True
    })
    
    print(f"품질: {validation['structured']['quality_score']}%")
    print(f"완전성: {completeness['structured']['completeness_score']}%")
    
    # 2. NULL 필드 분석
    null_analysis = completeness["structured"]["null_analysis"]
    critical_nulls = [
        field for field, info in null_analysis.items()
        if info["null_rate"] > 30  # 30% 이상 NULL
    ]
    
    if critical_nulls:
        print(f"⚠️ 심각한 NULL 필드: {critical_nulls}")
        # 해당 필드는 기본값으로 채우거나 재수집
    
    # 3. 이슈 수정 (데이터 제거 없이)
    fixed = await fix_data_issues({
        "domain": "법률",
        "data_records": law_data,
        "fill_missing_with_default": True,
        "default_values": {
            "status": "유효",
            "category": "기타법령",
            "view_count": 0
        },
        "convert_types": True,
        "trim_strings": True
    })
    
    # 4. 중복만 제거 (보수적)
    cleaned = await clean_data({
        "domain": "법률",
        "data_records": fixed["structured"]["fixed_records"],
        "remove_duplicates": True,
        "remove_nulls": False,  # NULL 유지
        "remove_anomalies": False  # 이상치 유지
    })
    
    final_data = cleaned["structured"]["cleaned_records"]
    print(f"최종 데이터: {len(final_data)}개")
    
    return final_data
```

---

## 🎯 도메인별 권장 전략

| 도메인 | 권장 전략 | 이유 |
|--------|-----------|------|
| 부동산 | 적극적 정제 | 데이터가 풍부하고 품질이 중요 |
| 법률 | 보수적 정제 | 데이터가 희소하고 모두 중요 |
| 채용 | 단계적 정제 | 품질이 다양하고 점진적 개선 필요 |
| 경매 | 보수적 정제 | 데이터가 시간에 민감 |

---

## 🔧 백엔드 연동 (Spring Boot)

```java
@Service
public class DataQualityService {
    
    @Autowired
    private McpHttpAdapter mcpAdapter;
    
    public List<Map<String, Object>> collectAndCleanData(
        String domain,
        String toolName,
        Map<String, Object> params
    ) {
        // 1. 데이터 수집
        McpExecutionResult rawData = mcpAdapter.callTool(toolName, params);
        List<Map<String, Object>> records = extractRecords(rawData);
        
        // 2. 품질 검증
        McpExecutionResult validation = mcpAdapter.callTool(
            "validate_data_quality",
            Map.of(
                "domain", domain,
                "data_records", records
            )
        );
        
        double qualityScore = extractQualityScore(validation);
        
        if (qualityScore < 70.0) {
            log.warn("품질 부족: {}%, 재수집 필요", qualityScore);
            throw new DataQualityException("품질 부족");
        }
        
        // 3. 데이터 정제
        McpExecutionResult cleaned = mcpAdapter.callTool(
            "clean_data",
            Map.of(
                "domain", domain,
                "data_records", records,
                "remove_duplicates", true,
                "remove_nulls", false,
                "remove_anomalies", false
            )
        );
        
        // 4. 이슈 수정
        List<Map<String, Object>> cleanedRecords = extractCleanedRecords(cleaned);
        
        McpExecutionResult fixed = mcpAdapter.callTool(
            "fix_data_issues",
            Map.of(
                "domain", domain,
                "data_records", cleanedRecords,
                "fill_missing_with_default", true,
                "convert_types", true,
                "trim_strings", true
            )
        );
        
        return extractFixedRecords(fixed);
    }
}
```

---

## ⚠️ 주의사항

### 데이터 손실 방지

1. **백업 필수**: 정제 전 원본 데이터 백업
2. **보수적 시작**: 처음에는 `remove_nulls=false`, `remove_anomalies=false`
3. **점진적 정제**: 한 번에 모든 옵션을 켜지 말고 단계적으로

### 성능 고려

1. **배치 처리**: 1000개 이상은 청크로 나눠 처리
2. **병렬 처리**: 여러 도구를 순차가 아닌 병렬로 호출
3. **캐싱**: 품질 검증 결과를 캐싱하여 재사용

### 로깅 및 모니터링

```python
# 품질 변화 추적
quality_log = {
    "timestamp": datetime.now().isoformat(),
    "domain": domain,
    "original_count": len(raw_data),
    "quality_score_before": validation_before["structured"]["quality_score"],
    "quality_score_after": validation_after["structured"]["quality_score"],
    "removed_count": removed_count,
    "fixes_applied": total_fixes
}

# 로그 저장 또는 알림
save_quality_log(quality_log)
```

---

## 📈 품질 점수 해석

| 점수 범위 | 상태 | 조치 |
|-----------|------|------|
| 90-100% | 매우 좋음 | 바로 사용 가능 |
| 70-89% | 좋음 | 간단한 정제 후 사용 |
| 50-69% | 보통 | 적극적 정제 필요 |
| 30-49% | 나쁨 | 재수집 또는 대량 수정 필요 |
| 0-29% | 매우 나쁨 | 재수집 필수 |

---

## 🚀 다음 단계

- [ ] 품질 트렌드 추적 도구 추가
- [ ] 자동 복구 규칙 학습 (ML)
- [ ] 실시간 품질 모니터링 대시보드
- [ ] 도메인별 품질 벤치마크 설정
