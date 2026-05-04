# 이상치 탐지 가이드

## 🔍 개요

`detect_anomalies`와 `clean_data`의 이상치 탐지 기능은 **통계적 방법(Z-score)**을 사용합니다.

---

## ⚙️ 작동 원리

### 1. 기본 알고리즘

```python
# 1단계: 평균 계산
mean = sum(values) / count

# 2단계: 표준편차 계산
variance = sum((x - mean)^2 for x in values) / count
std_dev = sqrt(variance)

# 3단계: 이상치 범위 설정
lower_bound = mean - (threshold * std_dev)
upper_bound = mean + (threshold * std_dev)

# 4단계: 범위 밖의 값을 이상치로 판정
if value < lower_bound or value > upper_bound:
    # 이상치!
```

### 2. Threshold (임계값)

| Threshold | Sigma | 정상 범위 | 설명 |
|-----------|-------|-----------|------|
| 1.0 | 1-sigma | 68.3% | 매우 엄격 (과도한 제거 위험) |
| 2.0 | 2-sigma | 95.4% | 엄격 |
| **3.0** | **3-sigma** | **99.7%** | **권장 (기본값)** |
| 4.0 | 4-sigma | 99.99% | 관대 |

---

## ⚠️ 주의사항

### 문제 1: 이상치가 통계를 왜곡

**극단적인 이상치는 평균과 표준편차를 크게 왜곡시킵니다.**

#### 나쁜 예시 (탐지 실패)

```python
data = [
    50000, 51000, 49000, 50500,  # 정상 (평균 50125)
    1000000  # 극단적 이상치 (100만)
]

# 문제: 1000000이 평균을 크게 올림
mean = 240100  # 원래는 50125여야 함
std_dev = 379878  # 엄청 커짐

# 2-sigma 범위
lower = 240100 - 2*379878 = -519656
upper = 240100 + 2*379878 = 999856

# 결과: 1000000도 범위 안에 들어감! (탐지 실패)
```

#### 좋은 예시 (탐지 성공)

```python
data = [
    50000, 51000, 49000, 50500,   # 정상
    49500, 50200, 50800, 49800,   # 정상 (더 많은 데이터)
    200000  # 이상치 (정상의 4배)
]

# 정상 데이터가 많아서 평균이 덜 왜곡됨
mean = 66644
std_dev = 47359

# 2-sigma 범위
lower = 66644 - 2*47359 = -28074
upper = 66644 + 2*47359 = 161362

# 결과: 200000은 범위 밖! (탐지 성공)
```

---

## ✅ 권장사항

### 1. 충분한 데이터 확보

**최소 데이터 개수:**
- 절대 최소: 3개 (통계적으로 의미 없음)
- 권장 최소: **10개 이상**
- 이상적: **30개 이상**

```python
# ❌ 나쁨: 데이터가 너무 적음
data = [50000, 51000, 1000000]  # 3개

# ✅ 좋음: 충분한 데이터
data = [50000, 51000, 49000, 50500, 49500, 50200, 
        50800, 49800, 51200, 49300, 200000]  # 11개
```

### 2. 정상 데이터 비율

**정상 데이터가 이상치보다 훨씬 많아야 합니다.**

```python
# ❌ 나쁨: 이상치 비율이 너무 높음 (50%)
data = [50000, 51000, 1000000, 2000000]  # 정상 50%, 이상치 50%

# ✅ 좋음: 정상 데이터가 대부분 (90%)
data = [50000, 51000, 49000, 50500, 49500, 
        50200, 50800, 49800, 51200, 200000]  # 정상 90%, 이상치 10%
```

### 3. 적절한 Threshold 선택

```python
# 데이터가 많고 품질이 좋음 → 엄격하게
await detect_anomalies({
    "data_records": many_records,
    "target_field": "price",
    "threshold": 2.0  # 2-sigma (95.4%)
})

# 데이터가 적거나 노이즈가 많음 → 관대하게
await detect_anomalies({
    "data_records": few_records,
    "target_field": "price",
    "threshold": 3.0  # 3-sigma (99.7%) - 기본값
})
```

### 4. 사전 정제

**이상치 탐지 전에 명백한 오류를 먼저 제거하세요.**

```python
# Step 1: 명백한 오류 제거
data_without_nulls = [r for r in data if r["price"] is not None]
data_positive = [r for r in data_without_nulls if r["price"] > 0]

# Step 2: 이상치 탐지
anomalies = await detect_anomalies({
    "data_records": data_positive,
    "target_field": "price",
    "threshold": 3.0
})
```

---

## 📊 실전 예시

### 예시 1: 부동산 데이터 (성공)

```python
# 강남구 아파트 100개 수집
data = await search_house_price({"region": "강남구", "deal_ymd": "202403"})
trades = data["structured"]["trades"]  # 100개

# 이상치 탐지 (데이터가 충분함)
result = await detect_anomalies({
    "domain": "부동산",
    "data_records": trades,
    "target_field": "deal_amount",
    "threshold": 3.0
})

# 통계
# mean: 120000 (12억)
# std_dev: 30000 (3억)
# 범위: 30000 ~ 210000 (3억 ~ 21억)

# 이상치 감지
# - 300000 (30억) → 이상치!
# - 10000 (1억) → 이상치!
```

### 예시 2: 소량 데이터 (실패 위험)

```python
# 경매 데이터 5개만 수집 (데이터 부족)
data = [
    {"estimate": 50000},
    {"estimate": 51000},
    {"estimate": 49000},
    {"estimate": 50500},
    {"estimate": 1000000}  # 극단적 이상치
]

result = await detect_anomalies({
    "domain": "경매",
    "data_records": data,
    "target_field": "estimate",
    "threshold": 3.0
})

# 문제: 이상치가 통계를 왜곡
# mean: 240100 (원래 50125여야 함)
# std_dev: 379878 (너무 큼)
# 결과: 1000000도 범위 안에 들어감 (탐지 실패!)
```

**해결책:**
```python
# 1. 더 많은 데이터 수집
# 2. 수동으로 명백한 오류 제거
# 3. 도메인 지식 활용 (예: 경매가는 5000만원 이하만 유효)

filtered = [r for r in data if r["estimate"] < 100000]  # 10억 이하만
result = await detect_anomalies({
    "data_records": filtered,
    "target_field": "estimate"
})
```

---

## 🎯 도메인별 권장 설정

### 부동산

```python
# 데이터가 풍부하고 범위가 좁음
await detect_anomalies({
    "domain": "부동산",
    "data_records": trades,
    "target_field": "deal_amount",
    "threshold": 2.5  # 약간 엄격
})
```

**특징:**
- 데이터 많음 (100개 이상)
- 가격 범위가 비교적 좁음
- 엄격한 기준 사용 가능

### 법률

```python
# 데이터가 적고 범위가 넓음
await detect_anomalies({
    "domain": "법률",
    "data_records": laws,
    "target_field": "view_count",
    "threshold": 3.5  # 관대
})
```

**특징:**
- 데이터 적음 (10~50개)
- 조회수 범위가 매우 넓음 (0 ~ 100만)
- 관대한 기준 사용

### 채용

```python
# 중간 수준
await detect_anomalies({
    "domain": "채용",
    "data_records": jobs,
    "target_field": "salary",
    "threshold": 3.0  # 기본값
})
```

---

## 🔧 대안 방법

### 1. 도메인 지식 활용

통계 대신 **상식적인 범위**를 직접 지정:

```python
# 통계 방법 (상황에 따라 실패 가능)
anomalies = await detect_anomalies(...)

# 도메인 지식 방법 (확실함)
VALID_RANGE = {
    "부동산": {"min": 10000, "max": 500000},  # 1억 ~ 50억
    "법률": {"min": 0, "max": 10000000},
    "채용": {"min": 0, "max": 200000}  # ~2억
}

# 직접 필터링
valid_trades = [
    trade for trade in trades
    if VALID_RANGE["부동산"]["min"] <= trade["deal_amount"] <= VALID_RANGE["부동산"]["max"]
]
```

### 2. 백분위수 방법

```python
# 상위/하위 5%를 이상치로 간주
import statistics

values = [r["price"] for r in data]
p5 = statistics.quantiles(values, n=20)[0]   # 하위 5%
p95 = statistics.quantiles(values, n=20)[18] # 상위 5%

outliers = [r for r in data if r["price"] < p5 or r["price"] > p95]
```

### 3. IQR (사분위수 범위) 방법

```python
# Q1(25%) ~ Q3(75%) 기준
import statistics

values = sorted([r["price"] for r in data])
q1 = statistics.quantiles(values, n=4)[0]
q3 = statistics.quantiles(values, n=4)[2]
iqr = q3 - q1

lower = q1 - 1.5 * iqr
upper = q3 + 1.5 * iqr

outliers = [r for r in data if r["price"] < lower or r["price"] > upper]
```

---

## ⚡ 빠른 체크리스트

이상치 탐지 전에 확인하세요:

- [ ] 데이터가 **10개 이상**인가?
- [ ] 정상 데이터가 **80% 이상**인가?
- [ ] NULL이나 음수는 **미리 제거**했는가?
- [ ] Threshold는 **3.0 (기본값)** 사용 중인가?
- [ ] 탐지가 실패하면 **도메인 지식**을 활용할 준비가 되었는가?

---

## 📚 추가 참고

- **Z-score 방법**: 정규분포를 가정, 데이터가 많을수록 정확
- **한계**: 극단적 이상치에 취약 (Robust 통계 필요)
- **대안**: MAD(Median Absolute Deviation), IQR 등

---

## 🚀 개선 계획

향후 추가 예정:
- [ ] Robust 통계 기반 이상치 탐지 (MAD)
- [ ] 머신러닝 기반 이상치 탐지 (Isolation Forest)
- [ ] 도메인별 사전 정의된 범위
- [ ] 시계열 이상치 탐지
