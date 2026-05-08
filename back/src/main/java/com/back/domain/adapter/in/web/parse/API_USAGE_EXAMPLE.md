# ParseTask API 사용 예시

## 📡 엔드포인트

### 1. 자연어 파싱 (초기 요청)
- **URL**: `POST /api/parse`
- **설명**: 사용자의 자연어 입력을 AI로 파싱하여 구조화된 데이터로 변환

### 2. 멀티턴 대화 계속하기
- **URL**: `POST /api/parse/continue/{sessionId}`
- **설명**: 모호한 입력에 대한 후속 응답을 받아서 파싱 완료

---

## 🔥 사용 예시

### 예시 1: 명확한 입력 (한 번에 완료)

```bash
# 요청
curl -X POST http://localhost:8080/api/parse \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "input": "강남 집값 5퍼센트 오르면 알려줘"
  }'

# 응답
{
  "sessionId": "sess-abc123",
  "tasks": [{
    "intent": "create",
    "domainName": "부동산",
    "query": "강남 아파트 가격",
    "condition": "5% 이상 상승",
    "cronExpr": "0 9 * * *",
    "channel": "discord",
    "apiType": "crawl",
    "target": "강남 지역 아파트 시세 변동",
    "urls": ["https://land.naver.com/..."],
    "confidence": 0.85,
    "needsConfirmation": false,
    "confirmationQuestion": ""
  }],
  "isComplete": true,
  "nextQuestion": null
}

→ ✅ isComplete=true이므로 바로 구독 생성 가능!
```

---

### 예시 2: 모호한 입력 (멀티턴 대화)

#### 1턴: 모호한 입력

```bash
# 요청
curl -X POST http://localhost:8080/api/parse \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "input": "집값 알려줘"
  }'

# 응답
{
  "sessionId": "sess-xyz789",
  "tasks": [{
    "intent": "create",
    "domainName": "부동산",
    "query": "집값",
    "condition": "",
    "cronExpr": "0 9 * * *",
    "channel": "discord",
    "apiType": "crawl",
    "target": "전국 또는 특정 지역 아파트 가격 변동",
    "urls": [],
    "confidence": 0.4,
    "needsConfirmation": true,
    "confirmationQuestion": "어느 지역의 어떤 조건으로 알려드릴까요?"
  }],
  "isComplete": false,
  "nextQuestion": "어느 지역의 어떤 조건으로 알려드릴까요?"
}

→ ❌ isComplete=false이므로 추가 정보 필요!
→ 사용자에게 nextQuestion 표시
```

#### 2턴: 정보 보완

```bash
# 요청
curl -X POST http://localhost:8080/api/parse/continue/sess-xyz789 \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "response": "강남, 5퍼센트 오르면"
  }'

# 응답
{
  "sessionId": "sess-xyz789",
  "tasks": [{
    "intent": "create",
    "domainName": "부동산",
    "query": "강남 아파트 가격",
    "condition": "5% 이상 상승",
    "cronExpr": "0 9 * * *",
    "channel": "discord",
    "apiType": "crawl",
    "target": "강남 지역 아파트 시세 변동",
    "urls": ["https://land.naver.com/..."],
    "confidence": 0.85,
    "needsConfirmation": false,
    "confirmationQuestion": ""
  }],
  "isComplete": true,
  "nextQuestion": null
}

→ ✅ isComplete=true! 이제 구독 생성 가능!
```

---

### 예시 3: 여러 모니터링 동시 요청

```bash
# 요청
curl -X POST http://localhost:8080/api/parse \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "input": "강남 집값 10% 오르면 알려주고, 백엔드 신입 채용 있으면 알려줘"
  }'

# 응답
{
  "sessionId": "sess-multi123",
  "tasks": [
    {
      "intent": "create",
      "domainName": "부동산",
      "query": "강남 아파트 가격",
      "condition": "10% 이상 상승",
      "cronExpr": "0 9 * * *",
      "needsConfirmation": false
    },
    {
      "intent": "create",
      "domainName": "채용",
      "query": "백엔드 개발자 신입 채용",
      "condition": "신입",
      "cronExpr": "0 9 * * 1",
      "needsConfirmation": false
    }
  ],
  "isComplete": true,
  "nextQuestion": null
}

→ ✅ 2개의 태스크 모두 완료! 각각 구독 생성 가능!
```

---

### 예시 4: 지원하지 않는 도메인

```bash
# 요청
curl -X POST http://localhost:8080/api/parse \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "input": "비트코인 가격 10% 오르면 알려줘"
  }'

# 응답
{
  "sessionId": "sess-reject456",
  "tasks": [{
    "intent": "reject",
    "domainName": "암호화폐",
    "query": "비트코인 가격",
    "condition": "지원하지 않는 도메인",
    "cronExpr": "",
    "channel": "",
    "apiType": "",
    "target": "",
    "urls": [],
    "confidence": 0.2,
    "needsConfirmation": false,
    "confirmationQuestion": ""
  }],
  "isComplete": true,
  "nextQuestion": null
}

→ ⚠️ intent="reject" - 지원하지 않는 도메인!
```

---

## 🎯 프론트엔드 연동 예시

```javascript
// 1. 초기 파싱 요청
async function parseUserInput(userId, input) {
  const response = await fetch('http://localhost:8080/api/parse', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, input })
  });
  
  const result = await response.json();
  
  if (result.isComplete) {
    console.log("✅ 파싱 완료! 구독 생성 가능");
    // TODO: 구독 생성 API 호출
    createSubscriptions(result.tasks);
  } else {
    console.log("❓ 추가 정보 필요:", result.nextQuestion);
    // 사용자에게 질문 표시
    const userResponse = await askUser(result.nextQuestion);
    // 후속 파싱
    await continueParseSession(userId, result.sessionId, userResponse);
  }
}

// 2. 후속 대화
async function continueParseSession(userId, sessionId, response) {
  const result = await fetch(
    `http://localhost:8080/api/parse/continue/${sessionId}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, response })
    }
  ).then(r => r.json());
  
  if (result.isComplete) {
    console.log("✅ 파싱 완료!");
    createSubscriptions(result.tasks);
  } else {
    console.log("❓ 추가 정보 필요:", result.nextQuestion);
  }
}
```

---

## 📋 TODO (추후 구현 예정)

1. **자동 구독 생성**: `needsConfirmation=false`인 태스크는 자동으로 `CreateSubscriptionService` 호출
2. **배치 구독 생성**: 여러 태스크를 한 번에 구독 생성
3. **에러 처리 개선**: AI 파싱 실패 시 재시도 로직
4. **세션 만료 처리**: 일정 시간 후 세션 자동 삭제
5. **사용자 인증**: 현재는 userId를 직접 받지만, 세션 쿠키로 인증 처리
