# Prompt Parser — 모니터링 태스크 파서 프로토타입

사용자의 자연어 모니터링 요청을 구조화된 JSON으로 변환하는 AI 프롬프트 파서입니다.

## 실행 방법

```bash
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt

# .env 파일 생성 후 API 키 입력
cp .env.example .env

# 테스트 실행
python runner.py
```

## 파일 구성

| 파일 | 설명 |
|------|------|
| `prompt.py` | System Prompt 및 빌더 |
| `runner.py` | API 호출, JSON 추출, 테스트 러너 |
| `test_cases.py` | 22개 테스트 케이스 정의 |
| `requirements.txt` | Python 의존성 |
| `.env.example` | 환경 변수 템플릿 |

## 출력 예시

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
      "urls": ["https://land.naver.com"],
      "confidence": 0.7,
      "needs_confirmation": true,
      "confirmation_question": "몇 % 이상 변동 시 알려드릴까요?"
    }
  }
]
```

## 설계 문서

상세 설계는 [docs/ai/prompt-template.md](../../docs/ai/prompt-template.md)를 참조하세요.
