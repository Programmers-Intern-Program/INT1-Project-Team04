---
owner: 프롬프트 엔지니어링
reviewer: 팀 전체
status: draft
last_updated: 2026-04-22
applies_to: monitoring-task-parser
---

# AI 프롬프트 템플릿 설계서 — 모니터링 태스크 파서

## 1. 문서 개요

- 문서명: AI 프롬프트 템플릿 설계서 — 모니터링 태스크 파서
- 버전: v0.4
- 작성 목적: 사용자의 자연어 모니터링 요청을 구조화된 JSON으로 파싱하는 프롬프트의 입력 변수, 출력 스키마, ERD 매핑, 검증 규칙, 재시도 정책, 다중 턴 대화 흐름을 정의한다.
- 적용 범위: 모니터링 태스크 파서 1차 MVP

## 2. 문서 목표

이 문서는 아래를 명확히 한다.

- 어떤 System Prompt와 Developer Prompt를 사용하는가
- 모델 응답을 어떤 JSON 구조로 강제하는가
- AI 출력 필드가 ERD의 어떤 테이블/컬럼에 매핑되는가
- 검증 실패 시 어떤 방식으로 재시도하는가
- Temperature 등 하이퍼파라미터는 어떻게 설정하는가

## 3. 범위와 전제

### 3.1 이번 문서에 포함하는 템플릿

- `monitoring.task.parse.v1`

### 3.2 출력 형식 기본 원칙

- 모든 핵심 응답은 항상 **JSON 배열** 형태다. 단일 요청이면 요소 1개인 배열로 반환한다.
- 설명 문장, 서론, 인사말, 코드 블록 마크다운은 금지한다.
- JSON parse 실패 시 코드 블록(````json ... ````) 제거 후 재시도한다.

## 4. ERD 매핑

### 4.1 ERD 테이블 구조

```text
domain (id, name)
mcp_server (id, name, description, endpoint)
users (id, email, kakao_token, created_at)
schedule (id, sub_id, cron_expr, last_run, next_run)
notification (id, schedule_id, user_id, ai_data_hub_id, channel, message, sent_at, status)
ai_data_hub (id, user_id, mcp_tool_id, api_type, content, embedding, metadata, created_at)
subscription (id, user_id, domain_id, query, intent, is_active, created_at)
mcp_tool (id, server_id, domain_id, name, description, input_schema)
```

### 4.2 AI 출력 → ERD 매핑

| AI 출력 필드 | ERD 테이블 | ERD 컬럼 | 비고 |
|---|---|---|---|
| `intent` | `subscription` | `intent` | 태스크 생성 의도 (create/delete/modify/reject) |
| `domain_name` | `subscription` | `domain_id` | domain.name으로 조회 후 id 매핑 |
| `query` | `subscription` | `query` | 모니터링 대상 요약 텍스트 |
| `condition` | `ai_data_hub` | `metadata` 내 포함 | 알림 조건 (수치 기반) |
| `cron_expr` | `schedule` | `cron_expr` | cron 표현식 |
| `channel` | `notification` | `channel` | 알림 채널 |
| `api_type` | `ai_data_hub` | `api_type` | 데이터 수집 방식 |
| `metadata.target` | `ai_data_hub` | `metadata` 내 포함 | 대상 상세 설명 |
| `metadata.urls` | `ai_data_hub` | `metadata` 내 포함 | 크롤링 URL 후보 |
| `metadata.confidence` | `ai_data_hub` | `metadata` 내 포함 | 파싱 신뢰도 |
| `metadata.needs_confirmation` | `ai_data_hub` | `metadata` 내 포함 | 사용자 확인 필요 여부 |
| `metadata.confirmation_question` | `ai_data_hub` | `metadata` 내 포함 | 재질문 내용 |

### 4.3 데이터 흐름

```text
사용자 자연어 입력
    ↓ AI 파서
JSON 배열 출력 (intent, domain_name, query, condition, cron_expr, channel, api_type, metadata)
    ↓
┌─────────────────────────────────────────────────────┐
│ intent 분기                                          │
│   create  → subscription + schedule + notification   │
│   delete  → subscription.is_active = 0 업데이트      │
│   modify  → schedule.cron_expr 업데이트               │
│   reject  → 응답만 반환 (DB 저장 없음)               │
├─────────────────────────────────────────────────────┤
│ subscription                                        │
│   intent     ← intent                               │
│   domain_id  ← domain 테이블에서 domain_name 조회    │
│   query      ← query                                │
│   is_active  ← intent별 설정                        │
├─────────────────────────────────────────────────────┤
│ schedule                                            │
│   sub_id    ← subscription.id                       │
│   cron_expr ← cron_expr                             │
├─────────────────────────────────────────────────────┤
│ notification                                        │
│   schedule_id ← schedule.id                         │
│   channel     ← channel                             │
│   status      ← 'PENDING' (기본값)                  │
├─────────────────────────────────────────────────────┤
│ ai_data_hub                                         │
│   api_type  ← api_type                              │
│   metadata  ← { target, urls, confidence, condition, │
│                 needs_confirmation,                  │
│                 confirmation_question }              │
└─────────────────────────────────────────────────────┘
```

## 5. 공통 프롬프트 원칙

### 5.1 공통 금지 규칙

- 지정된 JSON 필드 외에 임의 필드를 추가하지 않는다.
- 마크다운, 코드 블록, 설명 문장, 서론, 후기는 출력하지 않는다.
- **사용자가 제공하지 않은 수치를 임의로 추정하지 않는다.** 모호한 표현은 condition을 빈 문자열로 두고 needs_confirmation을 true로 설정한다.
- 지원하지 않는 도메인(주식, 암호화폐, 구독 등)은 intent를 "reject"로 설정한다.
- reject는 최종 거절이므로 needs_confirmation을 false로, confirmation_question을 빈 문자열로 설정한다.

### 5.2 공통 품질 플래그

- `needs_confirmation: true`: condition이나 대상이 불명확하여 사용자 확인이 필요함
- `intent: "reject"`: 지원하지 않는 도메인이거나 모니터링과 무관한 요청

---

## 6. 템플릿 상세 정의

## 6.1 monitoring.task.parse.v1

### 6.1.1 목적

- 사용자의 자연어 모니터링 요청을 분석해 구조화된 JSON 배열로 변환한다.
- 변환 결과는 intent에 따라 `subscription`, `schedule`, `notification`, `ai_data_hub` 테이블에 저장되거나 거절 응답으로 처리된다.

### 6.1.2 입력 변수

| 변수 | 타입 | 설명 | 필수 |
|------|------|------|------|
| `user_input` | string | 사용자의 자연어 요청 | O |

### 6.1.3 입력 예시

```text
"강남 투룸 시세 바뀌면 알려줘"
"테슬라 주가 5% 이상 오르면 디스코드로 알려줘"
"아까 등록한 강남 시세 알림 취소해줘"
"강남이랑 서초 둘 다 시세 알려줘"
```

### 6.1.4 System Prompt

```text
너는 모니터링 태스크 파서야.
사용자의 자연어 요청을 분석해서 아래 JSON 형식으로만 응답해.
다른 말은 절대 하지 마. JSON만 반환해.

응답은 항상 배열 형태야. 단일 요청이면 요소 1개인 배열로 반환해.

[
  {
    "intent": "create | delete | modify | reject",
    "domain_name": "도메인 이름",
    "query": "모니터링 대상 요약",
    "condition": "알림 조건",
    "cron_expr": "cron 표현식",
    "channel": "discord | email | telegram",
    "api_type": "crawl | api | rss | search",
    "metadata": {
      "target": "모니터링 대상 상세 설명",
      "urls": ["추천 URL 후보"],
      "confidence": 0.00~1.00,
      "needs_confirmation": true/false,
      "confirmation_question": "사용자에게 재질문할 내용 (needs_confirmation이 false면 빈 문자열)"
    }
  }
]

intent 분류:
- create: 새 모니터링 태스크 등록
- delete: 기존 태스크 삭제 ("취소해줘", "알림 끄기", "등록한 거 삭제")
- modify: 기존 태스크 수정 ("조건 바꿔줘", "시간 변경해줘", "평일만 체크해줘")
- reject: 지원하지 않는 도메인, 대상이 불명확하거나, 모니터링과 무관한 요청

지원 도메인 (이 4개만):
- 부동산: 집값, 시세, 월세, 전세, 부동산 관련
- 법률: 법령 변경, 판례, 법률 개정 관련
- 채용: 채용공고, 채용, jobs 관련
- 경매: 경매, 공매, 경매물건 관련

미지원 도메인 처리:
- 주식, 암호화폐, 구독 등 위 4개 도메인에 해당하지 않는 모니터링 요청은 intent를 "reject"로 설정
- domain_name에 감지된 도메인명(예: "주식", "암호화폐")을 넣고 condition을 "지원하지 않는 도메인"으로 설정
- 모니터링 대상이 너무 모호해서 4개 지원 도메인 중 어느 것에도 특정할 수 없으면 intent를 "reject"로 하고 domain_name을 "기타"로 설정
- reject인 경우 cron_expr, channel, api_type은 빈 문자열(""), urls은 빈 배열([])로 설정
- reject는 최종 거절이므로 needs_confirmation을 false로, confirmation_question을 빈 문자열("")로 설정

cron_expr 규칙:
- 매시간: "0 * * * *"
- 매일: "0 9 * * *"
- 매주: "0 9 * * 1"
- 평일만: "0 9 * * 1-5"
- "내일 아침", "오늘 오후" 같은 표현에서도 시간 부분을 cron_expr로 추출
- "바로", "즉시"는 데이터 특성에 맞는 기본 cron 사용 + confirmation_question에서 주기 확인
- delete/reject 요청은 빈 문자열("")
- modify 요청에서 새 cron이 명시되면 그 값을 설정

api_type 규칙:
- crawl: 웹페이지 스크래핑 (부동산 시세, 채용공고 등 대부분의 웹 데이터)
- api: 공개 API (공공데이터포털, 공식 API가 있는 경우만)
- rss: RSS 피드
- search: 검색 API
- 부동산 시세/집값은 기본적으로 "crawl"
- delete/reject 요청은 빈 문자열("")

channel 규칙:
- 채널 언급 없으면 discord 기본값
- delete/reject 요청은 빈 문자열("")

condition 규칙:
- 백엔드에서 비교 연산에 사용되므로 반드시 수치 기반
- 명시된 수치는 그대로 사용: "5% 이상 상승", "50만원 이하"
- 비유 표현은 수치 변환: "반토막" → "50% 하락"
- 모호한 표현은 condition을 빈 문자열("") + needs_confirmation: true
- delete → "삭제 요청", reject → "지원하지 않는 도메인"

urls 규칙:
- create intent에서는 needs_confirmation 여부와 상관없이 기본 URL 후보 제공
- reject/delete 요청은 빈 배열([])

needs_confirmation 판단 기준:
- condition이 비어있거나 모호 → true
- 모니터링 대상(지역, 회사 등)이 누락 → true
- 도메인, 대상, 조건이 모두 명확 → false
- delete/modify → 항상 true
- reject → 항상 false

target 작성 규칙:
- 항상 구체적이고 서술형으로 작성 (브리핑 생성에 사용)
- 짧은 입력도 의미를 확장: "집값" → "전국 또는 특정 지역 아파트/주택 가격 변동"
- 지역, 대상, 데이터 종류 포함

공통 규칙:
- delete/modify 요청은 confidence 0.50 이하
- reject 요청은 confidence 0.20 이하

오타/특수문자:
- 오타, 중복 문자, 이모티콘이 있어도 문맥으로 파악

복수 대상:
- 여러 대상은 각각 별도 태스크로 분리해서 배열의 각 요소로 반환

일회성 vs 반복:
- "한 번만" → cron_expr은 시간 표현 있으면 추출, needs_confirmation: true
- 반복 모니터링이 명확하면 기존 규칙대로 cron_expr 설정

삭제/수정 요청:
- delete/modify는 needs_confirmation을 true로, confirmation_question은 구체적으로 작성
- 예: "현재 등록된 태스크 목록을 보여드릴까요?"
```

### 6.1.5 Developer Prompt (build_prompt)

```python
def build_prompt(user_input: str) -> str:
    return f"사용자 요청: {user_input}"
```

### 6.1.6 출력 스키마

```json
[
  {
    "intent": "string (create | delete | modify | reject)",
    "domain_name": "string (부동산 | 법률 | 채용 | 경매 | 주식 | 암호화폐 | 구독 | 기타)",
    "query": "string (모니터링 대상 요약)",
    "condition": "string (수치 기반 알림 조건, 모호하면 빈 문자열)",
    "cron_expr": "string (cron 표현식, delete/reject면 빈 문자열)",
    "channel": "string (discord | email | telegram, delete/reject면 빈 문자열)",
    "api_type": "string (crawl | api | rss | search, delete/reject면 빈 문자열)",
    "metadata": {
      "target": "string (모니터링 대상 상세 서술)",
      "urls": ["string (추천 URL)"],
      "confidence": "float (0.00 ~ 1.00)",
      "needs_confirmation": "boolean",
      "confirmation_question": "string (needs_confirmation이 false면 빈 문자열)"
    }
  }
]
```

### 6.1.7 출력 예시

**입력:** `"강남 투룸 시세 바뀌면 알려줘"` (모호한 조건)

```json
[
  {
    "intent": "create",
    "domain_name": "부동산",
    "query": "강남 투룸 시세",
    "condition": "",
    "cron_expr": "0 9 * * *",
    "channel": "discord",
    "api_type": "crawl",
    "metadata": {
      "target": "강남 지역 투룸 아파트/주택 시세 변동",
      "urls": [
        "https://land.naver.com",
        "https://realestate.daum.net",
        "https://www.r114.com"
      ],
      "confidence": 0.7,
      "needs_confirmation": true,
      "confirmation_question": "강남 투룸 시세가 몇 % 이상 변동 시 알려드릴까요?"
    }
  }
]
```

**입력:** `"테슬라 주가 5% 이상 오르면 디스코드로 알려줘"` (미지원 도메인)

```json
[
  {
    "intent": "reject",
    "domain_name": "주식",
    "query": "테슬라 주가 5% 이상 오르면 디스코드로 알려줘",
    "condition": "지원하지 않는 도메인",
    "cron_expr": "",
    "channel": "",
    "api_type": "",
    "metadata": {
      "target": "테슬라 주가",
      "urls": [],
      "confidence": 0.2,
      "needs_confirmation": false,
      "confirmation_question": ""
    }
  }
]
```

**입력:** `"카카오 백엔드 공고 중 경력 3년 이하 포지션 나오면 매시간 체크해줘"` (명확한 조건)

```json
[
  {
    "intent": "create",
    "domain_name": "채용",
    "query": "카카오 백엔드 경력 3년 이하 채용공고",
    "condition": "경력 3년 이하",
    "cron_expr": "0 * * * *",
    "channel": "discord",
    "api_type": "crawl",
    "metadata": {
      "target": "카카오 careers 백엔드 채용공고 중 경력 3년 이하 포지션",
      "urls": [
        "https://careers.kakao.com/jobs"
      ],
      "confidence": 0.9,
      "needs_confirmation": false,
      "confirmation_question": ""
    }
  }
]
```

**입력:** `"아까 등록한 강남 시세 알림 취소해줘"` (삭제 요청)

```json
[
  {
    "intent": "delete",
    "domain_name": "",
    "query": "강남 시세 알림 취소 요청",
    "condition": "삭제 요청",
    "cron_expr": "",
    "channel": "",
    "api_type": "",
    "metadata": {
      "target": "사용자가 등록한 강남 시세 모니터링 태스크",
      "urls": [],
      "confidence": 0.4,
      "needs_confirmation": true,
      "confirmation_question": "현재 등록된 태스크 목록을 보여드릴까요?"
    }
  }
]
```

**입력:** `"강남이랑 서초 둘 다 시세 알려줘"` (복수 대상)

```json
[
  {
    "intent": "create",
    "domain_name": "부동산",
    "query": "강남 시세",
    "condition": "",
    "cron_expr": "0 9 * * *",
    "channel": "discord",
    "api_type": "crawl",
    "metadata": {
      "target": "강남 지역 아파트/주택 시세 변동",
      "urls": ["https://land.naver.com", "https://www.r114.com"],
      "confidence": 0.7,
      "needs_confirmation": true,
      "confirmation_question": "몇 % 이상 변동 시 알려드릴까요?"
    }
  },
  {
    "intent": "create",
    "domain_name": "부동산",
    "query": "서초 시세",
    "condition": "",
    "cron_expr": "0 9 * * *",
    "channel": "discord",
    "api_type": "crawl",
    "metadata": {
      "target": "서초 지역 아파트/주택 시세 변동",
      "urls": ["https://land.naver.com", "https://www.r114.com"],
      "confidence": 0.7,
      "needs_confirmation": true,
      "confirmation_question": "몇 % 이상 변동 시 알려드릴까요?"
    }
  }
]
```

**입력:** `"오늘 점심 뭐 먹지"` (무관한 입력)

```json
[
  {
    "intent": "reject",
    "domain_name": "기타",
    "query": "식사 메뉴 추천",
    "condition": "지원하지 않는 도메인",
    "cron_expr": "",
    "channel": "",
    "api_type": "",
    "metadata": {
      "target": "모니터링과 무관한 요청",
      "urls": [],
      "confidence": 0.1,
      "needs_confirmation": false,
      "confirmation_question": ""
    }
  }
]
```

### 6.1.8 후처리 검증

| 검증 항목 | 조건 | 실패 시 처리 |
|-----------|------|-------------|
| JSON parse | 올바른 JSON 배열이어야 함 | 코드 블록 제거 후 재시도 (최대 2회) |
| 응답 형식 | 항상 배열 (단일 객체도 배열로 래핑) | extract_json에서 단일 객체를 배열로 래핑 |
| 필수 필드 | intent, domain_name, query, condition, cron_expr, channel, api_type, metadata 모두 존재 | 재시도 |
| intent enum | `create`, `delete`, `modify`, `reject` 중 하나 | 재시도 |
| domain_name | 지원 도메인: 부동산, 법률, 채용, 경매. 미지원도 명시 가능 (주식 등) | 허용 (intent로 구분) |
| cron_expr 형식 | 유효한 5필드 cron 또는 빈 문자열 | 기본값 `0 9 * * *`로 보정 |
| channel enum | `discord`, `email`, `telegram` 또는 빈 문자열 | 기본값 `discord`로 보정 |
| api_type enum | `crawl`, `api`, `rss`, `search` 또는 빈 문자열 | 기본값 `crawl`로 보정 |
| metadata.confidence | 0.00 ~ 1.00 float, 소수점 둘째 자리 반올림 | 클램핑 |
| metadata.needs_confirmation | boolean | 기본값 false |
| metadata.confirmation_question | needs_confirmation이 true면 비어있지 않은 문자열 | 경고 |
| reject 일관성 | intent가 reject면 needs_confirmation=false, confirmation_question="", urls=[], cron_expr/channel/api_type="" | 보정 |

### 6.1.9 ERD 저장 시나리오

```
1. AI 파서 응답 수신 (JSON 배열)
2. 배열의 각 요소에 대해:

   [intent 분기]
   - reject → 응답만 반환 (DB 저장 없음)
   - create → 아래 3~6번 수행
   - delete → subscription.is_active = 0 업데이트 (태스크 식별 불가 → 사용자 확인 필요)
   - modify → schedule 업데이트 (태스크 식별 불가 → 사용자 확인 필요)

3. domain_name → domain 테이블에서 id 조회
   - 없으면 domain 테이블에 새 row INSERT 후 id 획득
4. subscription INSERT
   - user_id: 현재 사용자 ID
   - domain_id: 3번에서 획득한 id
   - intent: intent 필드값
   - query: query 필드값
   - is_active: 1
5. schedule INSERT
   - sub_id: 4번에서 생성된 subscription.id
   - cron_expr: cron_expr 필드값
6. notification + ai_data_hub INSERT
   - notification: schedule_id, channel, status='PENDING'
   - ai_data_hub: api_type, metadata={target, urls, confidence, condition, needs_confirmation, confirmation_question}
```

### 6.1.10 재시도 규칙

- malformed JSON: 동일 payload 최대 2회 재시도
- 코드 블록 감싸진 응답: ````json ... ```` 제거 후 파싱
- API 빈 응답: 빈 문자열 반환 → `'[]'`로 폴백
- JSON 추출 실패: 원본 응답 디버그 출력 후 `'[]'` 폴백
- 최대 재시도: 2회

---

## 7. 하이퍼파라미터 설정

| 파라미터 | 값 | 설명 |
|----------|-----|------|
| model | glm-4.5 | Z.AI GLM-4.5 |
| temperature | 0.3 | 파싱/추출 용도로 낮은 값 유지 |
| max_tokens | 2048 | 복수 태스크 배열 응답을 고려한 충분한 길이 |

### Temperature 가이드

| 값 | 용도 | 특징 |
|----|------|------|
| 0.0 - 0.2 | 분류, 파싱, 추출 | 결정적, 일관된 응답 |
| 0.3 - 0.5 | 요약, 번역, 변환 | 약간의 창의성 |
| 0.6 - 0.8 | 글쓰기, 브레인스토밍 | 다양한 표현 |
| 0.9 - 1.0 | 창작, 아이디어 생성 | 최대 다양성 |

---

## 8. 프롬프트 튜닝 체크리스트

| 항목 | 현재 상태 | 비고 |
|------|----------|------|
| 역할이 구체적인가? | O | 모니터링 태스크 파서 |
| 출력 형식이 명확한가? | O | 항상 JSON 배열 |
| intent 분류가 명확한가? | O | create/delete/modify/reject |
| 지원 도메인이 명시되어 있는가? | O | 부동산, 법률, 채용, 경매 |
| condition 수치 기반 원칙이 있는가? | O | 모호하면 빈 문자열 + confirmation |
| needs_confirmation 판단 기준이 있는가? | O | 명확한 기준 정의 |
| target 서술형 작성 규칙이 있는가? | O | 브리핑 생성 고려 |
| 복수 대상 처리가 있는가? | O | 배열의 각 요소로 분리 |
| 삭제/수정 요청 처리가 있는가? | O | delete/modify intent |
| 무관/미지원 입력 처리가 있는가? | O | reject intent |
| Few-shot 예시가 있는가? | O | 6개 예시 포함 |
| 다중 턴 대화가 지원되는가? | O | Strategy C(프로그래밍) + A(AI 히스토리) 혼합 |
| 세션 관리가 있는가? | O | ConversationSession (최대 3턴) |

---

## 9. 원래 ERD에서 변경된 부분 (v0.2 → v0.3)

### 9.1 출력 스키마 변경

| 항목 | v0.2 (이전) | v0.3 (현재) | 변경 사유 |
|------|------------|------------|----------|
| **응답 형식** | 단일 JSON object | 항상 JSON 배열 `[{...}]` | 복수 대상 처리 일관성 (프론트/백 파싱 단순화) |
| **intent 필드** | 없음 | `create \| delete \| modify \| reject` | 삭제/수정/거절 요청 구분 필요 |
| **metadata.needs_confirmation** | 없음 | `boolean` | 모호한 입력에 대한 사용자 확인 플로우 |
| **metadata.confirmation_question** | 없음 | `string` | AI가 어떤 재질문을 해야 하는지 명시 |
| **condition** | 자연어 허용 | 수치 기반만, 모호하면 빈 문자열 | 백엔드 비교 연산 파싱 불가 문제 해결 |

### 9.2 도메인 분류 변경

| 항목 | v0.2 (이전) | v0.3 (현재) |
|------|------------|------------|
| 지원 도메인 | 부동산, 주식, 채용, 암호화폐, 구독, 기타 | **부동산, 법률, 채용, 경매** (4개만) |
| 미지원 처리 | domain_name "기타"로 분류 | **intent: "reject"** + 감지된 도메인명 유지 |

### 9.3 검증 규칙 변경

| 항목 | v0.2 (이전) | v0.3 (현재) |
|------|------------|------------|
| domain_name enum | 부동산, 주식, 채용, 암호화폐, 구독, 기타 | 제한 없음 (intent로 지원/미지원 구분) |
| reject 일관성 검증 | 없음 | needs_confirmation=false, 빈 필드 일관성 검증 추가 |
| confirmation_question 검증 | 없음 | needs_confirmation=true일 때 비어있지 않은지 검증 |

### 9.4 ERD 매핑 변경

| 항목 | v0.2 (이전) | v0.3 (현재) |
|------|------------|------------|
| `subscription` 테이블 | domain_id, query만 매핑 | **intent 컬럼 추가** 매핑 |
| 데이터 흐름 | 무조건 INSERT | **intent 분기** (create→INSERT, delete→비활성화, modify→업데이트, reject→미저장) |
| ai_data_hub metadata | target, urls, confidence, condition | **needs_confirmation, confirmation_question 추가** |

### 9.5 하이퍼파라미터 변경

| 항목 | v0.2 (이전) | v0.3 (현재) |
|------|------------|------------|
| max_tokens | 1000 | **2048** (복수 태스크 배열 응답 고려) |

### 9.6 v0.3 → v0.4 변경 내역 (다중 턴 대화 추가)

| 항목 | v0.3 | v0.4 | 변경 사유 |
|------|------|------|----------|
| 다중 턴 대화 | 없음 | **Strategy C + A 혼합** | needs_confirmation에 대한 사용자 답변 처리 |
| CONTINUE_SYSTEM_PROMPT | 없음 | **후속 대화용 시스템 프롬프트** | Strategy A에서 이전 결과 기반 업데이트 지시 |
| ConversationSession | 없음 | **세션 상태 클래스** | 다중 턴 대화 상태 관리 (최대 3턴) |
| 프로그래밍 병합 | 없음 | **percentage/channel/boolean 감지** | AI 호출 없이 즉시 응답 |
| 테스트 | 단일 턴 22개 | **+ 다중 턴 14개** | 멀티 턴 시나리오 검증 |

| 항목 | v0.2 (이전) | v0.3 (현재) |
|------|------------|------------|
| max_tokens | 1000 | **2048** (복수 태스크 배열 응답 고려) |

---

## 10. 다중 턴 대화 (Multi-Turn)

### 10.1 개요

1차 파싱 결과에서 `needs_confirmation: true`가 반환되면, 사용자의 추가 답변을 받아 결과를 업데이트한다. 두 가지 전략을 혼합하여 사용한다.

### 10.2 전략 비교

| | Strategy C (프로그래밍 병합) | Strategy A (AI 대화 히스토리) |
|---|---|---|
| **비용** | 무료 (API 미호출) | API 1회 호출 |
| **속도** | 즉시 | 네트워크 지연 |
| **처리 가능** | 퍼센티지, 채널, Yes/No | 모든 자연어 변경 |
| **판별 기준** | confirmation_question 키워드 매칭 | C에서 감지 실패시 자동 폴백 |

### 10.3 전체 호출 흐름

```text
사용자: "강남 집값 오르면 알려줘"
  ↓ start_session()
  ↓ call_api() → GLM API (SYSTEM_PROMPT)
  ← 1차 결과: { condition:"", needs_confirmation:true, question:"몇 % 이상 변동 시?" }
  ↓
  화면에 confirmation_question 표시
  ↓
사용자: "4% 이상 오르면"
  ↓ continue_task(session, "4% 이상 오르면")
  ↓
  ┌─ Strategy C 시도 ─────────────────────────┐
  │ _detect_percentage("4% 이상 오르면") → 4.0 │
  │ _is_threshold_question("몇 % 이상 변동") → true │
  │ condition = "4.0% 이상 변동"               │
  │ needs_confirmation = false                 │
  │ return 업데이트 결과 (API 호출 없음)       │
  └────────────────────────────────────────────┘
  ↓
최종 결과: { condition:"4.0% 이상 변동", needs_confirmation:false }
```

### 10.4 Strategy C: 프로그래밍 방식 병합

confirmation_question의 내용을 분석하여 사용자 응답을 자동 병합한다.

#### 감지 패턴

| 패턴 | 감지 함수 | 예시 입력 | 결과 |
|------|----------|----------|------|
| 퍼센티지 | `_detect_percentage()` | "4%", "5프로", "3.5퍼센트" | condition 업데이트 |
| 채널 | `_detect_channel()` | "디스코드", "이메일", "텔레그램" | channel 업데이트 |
| Yes/No | `_detect_boolean()` | "네", "맞아요", "아니요" | needs_confirmation 업데이트 |

#### 질문 분류 휴리스틱

감지된 값이 어떤 질문에 대한 답인지 판별한다.

```python
# 임계값 질문인지 확인 (% 언급 → percentage 감지 적용)
_is_threshold_question(question) → "%", "프로", "퍼센트", "몇", "수치", "기준" 포함 여부

# 채널 질문인지 확인 (채널 언급 → channel 감지 적용)
_is_channel_question(question) → "채널", "디스코드", "이메일", "텔레그램", "어디로" 포함 여부

# Yes/No 질문인지 확인
_is_boolean_question(question) → "할까요?", "맞나요?", "보여드릴까요?" 포함 여부
```

### 10.5 Strategy A: 대화 히스토리 기반 병합

Strategy C로 처리할 수 없는 복잡한 응답은 전체 대화 히스토리를 AI에 전달한다.

#### CONTINUE_SYSTEM_PROMPT (후속 대화용 시스템 프롬프트)

```text
너는 모니터링 태스크 파서의 후속 대화 모드야.
이전 파싱 결과와 사용자의 추가 답변을 받아서 업데이트된 JSON을 반환해.
다른 말은 절대 하지 마. JSON만 반환해.

규칙:
1. 이전 JSON 결과를 기반으로 사용자의 추가 입력을 반영해 전체 JSON을 업데이트해.
2. 사용자가 명시하지 않은 필드는 이전 값을 그대로 유지해.
3. condition 필드에 사용자가 제공한 수치를 반영해.
4. 모든 모호성이 해결되면 needs_confirmation을 false로 설정.
5. 여전히 모호하면 needs_confirmation을 true로 유지하고 새 confirmation_question 작성.
6. 동일한 JSON 스키마 사용, 항상 배열로 반환.
7. confidence는 이전 값보다 약간 높게 설정.
```

#### API 호출 구조

```python
messages = [
    {"role": "system",      "content": CONTINUE_SYSTEM_PROMPT},
    {"role": "user",        "content": "사용자 요청: 강남 집값 오르면 알려줘"},
    {"role": "assistant",   "content": "[{ ... condition:'', needs_confirmation:true ... }]"},
    {"role": "user",        "content": "강남 말고 서초로 바꿔줘"}  # ← 새로운 사용자 응답
]
```

### 10.6 세션 관리

```python
@dataclass
class ConversationSession:
    session_id: str              # 고유 세션 ID
    original_input: str          # 최초 사용자 입력
    messages: list               # 대화 히스토리 [{role, content}, ...]
    current_result: list         # 현재 JSON 결과
    turn_count: int              # 확인 라운드 수
    max_turns: int = 3           # 최대 라운드 (초과시 강제 완료)
    is_complete: bool            # 모든 confirmation 해결 여부
```

| 함수 | 역할 |
|------|------|
| `start_session(user_input)` | 1차 파싱 → 세션 생성 |
| `continue_task(session, user_response)` | Strategy C 시도 → 실패시 A 폴백 → 세션 업데이트 |

### 10.7 멀티 턴 시나리오 예시

**시나리오 1: 퍼센티지 응답 (Strategy C)**

```text
1차: "강남 집값 오르면" → condition:"", question:"몇 % 이상 변동 시?"
2차: "4% 이상 오르면" → condition:"4.0% 이상 변동", needs_confirmation:false
```

**시나리오 2: 복합 변경 (C → A)**

```text
1차: "강남 집값 오르면" → condition:"", question:"몇 % 이상?"
2차: "5% 이상" → condition:"5.0% 이상 변동" (Strategy C)
3차: "디스코드 말고 이메일로 바꿔줘" → channel:"email" (Strategy A)
```

**시나리오 3: reject → 지원 도메인 전환 (Strategy A)**

```text
1차: "테슬라 주가 오르면" → intent:"reject", domain:"주식"
2차: "그럼 강남 아파트 시세로 대신 해줘" → intent:"create", domain:"부동산" (Strategy A)
```

**시나리오 4: 복수 태스크 일괄 조건 (Strategy C)**

```text
1차: "강남이랑 서초 둘 다 시세 알려줘" → 2개 태스크, condition:""
2차: "5% 이상 변동하면" → 두 태스크 모두 condition:"5.0% 이상 변동" (Strategy C)
```

**시나리오 5: 최대 턴 초과 강제 완료**

```text
1차: "집값 알려줘" → condition:"", question:"어느 지역?"
2차: "음..." → AI 폴백, 여전히 모호 (Strategy A)
3차: "그냥..." → AI 폴백, 여전히 모호 (Strategy A)
4차: "아무거나" → max_turns(3) 초과 → 강제로 needs_confirmation=false
```

### 10.8 멀티 턴 테스트 케이스

`python runner.py --multi`로 실행. 총 14개 시나리오.

| # | 초기 입력 | 후속 응답 | 예상 전략 | 검증 항목 |
|---|----------|----------|----------|----------|
| 1 | 강남 집값 좀 많이 오르면 | 4% 이상 오르면 | C | condition |
| 2 | 집값 알려줘 | 텔레그램으로 보내줘 | A | channel |
| 3 | 강남 투룸 시세 바뀌면 | 강남 말고 서초로 | A | domain |
| 4 | 강남 집값 오르면 | 5% 이상 → 이메일로 | C→A | condition+channel |
| 5 | 아까 등록한 알림 취소해줘 | 네 맞아요 | C | needs_confirm=false |
| 6 | 마포구 원룸 월세 알려줘 | 30만원 이하로 | A | condition |
| 7 | 집값 알려줘 | 음...→그냥...→아무거나 | A×3 | 강제 완료 |
| 8 | 채용공고 알려줘 | 백엔드+경력3년+이메일 | A | domain+channel |
| 9 | 강남 투룸 시세 바뀌면 | 3프로 이상 변동하면 | C | condition |
| 10 | 야 강남 집값 오르면ㅋㅋ | 10% 이상 오르면 | C | condition |
| 11 | 강남이랑 서초 둘 다 | 5% 이상 변동하면 | C | condition (2개) |
| 12 | 주말에는 쉬고 평일만 | 강남 시세, 오전 8시로 | A | domain |
| 13 | 강남 투룸 시세!!!! 바뀌면ㅠㅠ | 7% 이상 변동하면 | C | condition |
| 14 | 테슬라 주가 5% 오르면 | 강남 아파트로 대신 | A | domain=부동산 |

---

## 11. 추천 다음 단계

- `domain_name` → `domain_id` 매핑 로직 구현 (지원 4개 도메인만 INSERT, 미지원은 reject 응답)
- intent별 DB 저장 분기 로직 구현 (create/delete/modify/reject)
- `needs_confirmation: true` 응답에 대한 사용자 확인 대화 플로우 구현
- `cron_expr` 유효성 검증 로직 추가
- `confirmation_question` 기반 자동 재질문 UI 구현
