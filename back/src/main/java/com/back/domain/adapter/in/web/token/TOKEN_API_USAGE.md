# 토큰 관리 API 사용 가이드

AI 토큰처럼 사용자의 서비스 사용량을 관리하는 토큰 시스템입니다.

## 📊 개요

- **토큰**: 사용자의 서비스 사용 가능 횟수를 나타내는 가상 화폐
- **사용 시나리오**: AI 파싱, 스케줄 실행 등 리소스를 소비하는 작업에 사용
- **관리 기능**: 토큰 조회, 사용(차감), 부여(충전), 사용 내역 조회

---

## 🔑 API 엔드포인트

### 1. 토큰 잔액 조회

**요청**
```bash
GET /api/tokens/balance/{userId}
```

**응답 예시**
```json
{
  "userId": 1,
  "balance": 500,
  "totalGranted": 1000,
  "totalUsed": 500,
  "lastUpdatedAt": "2026-04-27T15:30:00"
}
```

**설명**
- `balance`: 현재 사용 가능한 토큰
- `totalGranted`: 지금까지 부여받은 총 토큰
- `totalUsed`: 지금까지 사용한 총 토큰

**curl 예시**
```bash
curl -X GET http://localhost:8080/api/tokens/balance/1
```

---

### 2. 토큰 사용 (차감)

**요청**
```bash
POST /api/tokens/use
Content-Type: application/json

{
  "userId": 1,
  "amount": 10,
  "description": "AI 자연어 파싱",
  "referenceId": "parse-session-abc123"
}
```

**응답 예시**
```json
{
  "userId": 1,
  "balance": 490,
  "totalGranted": 1000,
  "totalUsed": 510,
  "lastUpdatedAt": "2026-04-27T15:35:00"
}
```

**에러 케이스**

1. **토큰 부족 (402 Payment Required)**
```json
{
  "success": false,
  "code": "INSUFFICIENT_TOKEN",
  "message": "토큰이 부족합니다.",
  "timestamp": "2026-04-27T15:35:00"
}
```

2. **잘못된 금액 (400 Bad Request)**
```json
{
  "success": false,
  "code": "INVALID_TOKEN_AMOUNT",
  "message": "토큰 금액이 올바르지 않습니다.",
  "timestamp": "2026-04-27T15:35:00"
}
```

**curl 예시**
```bash
curl -X POST http://localhost:8080/api/tokens/use \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "amount": 10,
    "description": "AI 자연어 파싱",
    "referenceId": "parse-session-abc123"
  }'
```

---

### 3. 토큰 부여 (충전)

**요청**
```bash
POST /api/tokens/grant
Content-Type: application/json

{
  "userId": 1,
  "amount": 100,
  "description": "월간 기본 지급"
}
```

**응답 예시**
```json
{
  "userId": 1,
  "balance": 590,
  "totalGranted": 1100,
  "totalUsed": 510,
  "lastUpdatedAt": "2026-04-27T16:00:00"
}
```

**curl 예시**
```bash
curl -X POST http://localhost:8080/api/tokens/grant \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "amount": 100,
    "description": "월간 기본 지급"
  }'
```

---

### 4. 토큰 사용 내역 조회

**요청**
```bash
GET /api/tokens/history/{userId}?limit=20
```

**응답 예시**
```json
{
  "history": [
    {
      "id": "hist-123",
      "type": "USE",
      "amount": 10,
      "balanceBefore": 500,
      "balanceAfter": 490,
      "description": "AI 자연어 파싱",
      "createdAt": "2026-04-27T15:35:00"
    },
    {
      "id": "hist-122",
      "type": "GRANT",
      "amount": 100,
      "balanceBefore": 400,
      "balanceAfter": 500,
      "description": "월간 기본 지급",
      "createdAt": "2026-04-27T15:00:00"
    }
  ]
}
```

**토큰 사용 유형 (type)**
- `GRANT`: 토큰 부여
- `USE`: 토큰 사용
- `REFUND`: 토큰 환불
- `ADMIN_ADJUSTMENT`: 관리자 조정

**curl 예시**
```bash
curl -X GET "http://localhost:8080/api/tokens/history/1?limit=20"
```

---

## 💡 사용 시나리오

### 시나리오 1: 신규 사용자 가입 시 초기 토큰 부여

```bash
# 1. 가입 웰컴 토큰 지급
POST /api/tokens/grant
{
  "userId": 123,
  "amount": 100,
  "description": "가입 축하 토큰"
}
```

### 시나리오 2: AI 파싱 시 토큰 차감

```java
// ParseTaskService.java 내부에서 호출
@Override
public ParseResult parse(ParseTaskCommand command) {
    // 1. 토큰 차감 (10 토큰 소비)
    try {
        tokenManagementUseCase.useToken(new UseTokenCommand(
            command.userId(),
            10,
            "AI 자연어 파싱",
            "parse-session-" + sessionId
        ));
    } catch (ApiException e) {
        if (e.getErrorCode() == ErrorCode.INSUFFICIENT_TOKEN) {
            throw new ApiException(ErrorCode.INSUFFICIENT_TOKEN);
        }
        throw e;
    }
    
    // 2. AI 파싱 수행
    List<ParsedTask> tasks = parseNaturalLanguagePort.parse(command.input());
    // ... 나머지 로직
}
```

### 시나리오 3: 월간 구독료 결제 후 토큰 충전

```bash
# 프리미엄 플랜 결제 완료 시
POST /api/tokens/grant
{
  "userId": 123,
  "amount": 1000,
  "description": "프리미엄 플랜 월간 토큰 (2026년 4월)"
}
```

### 시나리오 4: 사용자 대시보드에서 토큰 정보 표시

```javascript
// Frontend 예시
async function loadUserTokenInfo(userId) {
  const response = await fetch(`/api/tokens/balance/${userId}`);
  const data = await response.json();
  
  console.log(`현재 토큰: ${data.balance}`);
  console.log(`사용률: ${(data.totalUsed / data.totalGranted * 100).toFixed(1)}%`);
}
```

---

## ⚙️ 통합 가이드

### ParseTaskService에 토큰 차감 추가

```java
@Service
@RequiredArgsConstructor
public class ParseTaskService implements ParseTaskUseCase {
    
    private final ParseNaturalLanguagePort parseNaturalLanguagePort;
    private final TokenManagementUseCase tokenManagementUseCase; // 추가
    
    @Override
    public ParseResult parse(ParseTaskCommand command) {
        // Step 0: 토큰 차감 (10 토큰)
        tokenManagementUseCase.useToken(new UseTokenCommand(
            command.userId(),
            10,
            "자연어 파싱 - " + command.input().substring(0, Math.min(50, command.input().length())),
            null
        ));
        
        // Step 1: AI로 자연어 파싱
        List<ParsedTask> tasks = parseNaturalLanguagePort.parse(command.input());
        
        // ... 기존 로직
    }
}
```

### ScheduleExecutionService에 토큰 차감 추가

```java
@Service
@RequiredArgsConstructor
public class ScheduleExecutionService {
    
    private final TokenManagementUseCase tokenManagementUseCase; // 추가
    
    public void runDueSchedules() {
        List<Schedule> dueSchedules = loadDueSchedulesPort.loadDueSchedules(LocalDateTime.now());
        
        for (Schedule schedule : dueSchedules) {
            try {
                // Step 0: 토큰 차감 (5 토큰)
                tokenManagementUseCase.useToken(new UseTokenCommand(
                    schedule.subscription().user().id(),
                    5,
                    "스케줄 실행 - " + schedule.subscription().query(),
                    schedule.id()
                ));
                
                // Step 1: 스케줄 실행
                // ... 기존 로직
            } catch (ApiException e) {
                if (e.getErrorCode() == ErrorCode.INSUFFICIENT_TOKEN) {
                    log.warn("토큰 부족으로 스케줄 실행 건너뜀 - scheduleId: {}", schedule.id());
                    continue; // 다음 스케줄로
                }
                throw e;
            }
        }
    }
}
```

---

## 🎯 토큰 가격 정책 예시

| 작업 | 토큰 소비량 | 설명 |
|------|-------------|------|
| AI 자연어 파싱 | 10 토큰 | ParseTaskService.parse() |
| AI 후속 파싱 | 5 토큰 | ParseTaskService.continueParse() |
| 스케줄 실행 | 5 토큰 | ScheduleExecutionService.execute() |
| 구독 생성 | 무료 | CreateSubscriptionService.create() |

**플랜별 월간 토큰**
- **Free**: 100 토큰/월
- **Basic**: 500 토큰/월
- **Premium**: 2000 토큰/월
- **Enterprise**: 무제한

---

## 🔍 데이터베이스 스키마

### user_tokens 테이블
```sql
CREATE TABLE user_tokens (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    balance INTEGER NOT NULL,
    total_granted INTEGER NOT NULL,
    total_used INTEGER NOT NULL,
    last_updated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### token_usage_history 테이블
```sql
CREATE TABLE token_usage_history (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount INTEGER NOT NULL,
    balance_before INTEGER NOT NULL,
    balance_after INTEGER NOT NULL,
    description TEXT,
    reference_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_token_history_user_created 
ON token_usage_history(user_id, created_at DESC);
```

---

## 📝 참고사항

1. **초기 토큰 생성**: 사용자가 처음 토큰 관련 API를 호출하면 잔액 0으로 자동 생성됩니다.
2. **트랜잭션**: 모든 토큰 변경은 트랜잭션 내에서 안전하게 처리됩니다.
3. **내역 보관**: 모든 토큰 변경 내역이 `token_usage_history` 테이블에 영구 저장됩니다.
4. **동시성**: 같은 사용자의 동시 토큰 사용 요청은 데이터베이스 레벨에서 안전하게 처리됩니다.
