TEST_CASES = [
    # === info intent: 서비스 소개 및 일반 대화 (토큰 0) ===
    {"input": "안녕", "expect_intent": "info"},
    {"input": "안녕하세요", "expect_intent": "info"},
    {"input": "하이", "expect_intent": "info"},
    {"input": "이거 뭐하는 프로그램이야?", "expect_intent": "info"},
    {"input": "뭐하는 서비스야?", "expect_intent": "info"},
    {"input": "지금 구독할 수 있는 도메인은 뭐야?", "expect_intent": "info"},
    {"input": "뭐 할 수 있어?", "expect_intent": "info"},
    {"input": "어떤 기능이 있어?", "expect_intent": "info"},
    {"input": "강남 시세가 어때?", "expect_intent": "info"},
    {"input": "요즘 채용공고 많아?", "expect_intent": "info"},
    {"input": "부동산 시세 알려줘", "expect_intent": "info"},
    {"input": "고마워", "expect_intent": "info"},
    {"input": "오늘 점심 뭐 먹지", "expect_intent": "info"},
    {"input": "심심해", "expect_intent": "info"},

    # === info vs create 경계 케이스 ===
    # "시세 바뀌면 알려줘"는 모니터링 의사가 명확하므로 create
    {"input": "강남 시세 바뀌면 알려줘", "expect_domain": "부동산", "expect_intent": "create"},

    # === 명확한 케이스 — 지원 도메인 ===
    {"input": "강남 투룸 시세 바뀌면 알려줘", "expect_domain": "부동산", "expect_cron": "0 9 * * *"},
    {"input": "넥슨 채용공고에 Java 3년 이상 뜨면 알려줘", "expect_domain": "채용"},

    # 미지원 도메인 — reject (명시적 모니터링 요청만)
    {"input": "테슬라 주가 5% 이상 오르면 디스코드로 알려줘", "expect_intent": "reject"},
    {"input": "비트코인 1억 넘으면 알려줘", "expect_intent": "reject"},

    # info vs reject: 지원 불가 도메인의 일반 질문 → info
    {"input": "테슬라 주가 어때?", "expect_intent": "info"},
    {"input": "비트코인 시세가 어떻게 돼?", "expect_intent": "info"},

    # 모호한 케이스 — needs_confirmation 필요
    {"input": "집값 알려줘", "expect_domain": "부동산", "expect_needs_confirm": True},

    # 복합 조건 — 지원 도메인
    {"input": "마포구 원룸 월세 50만원 이하로 내려가면 이메일로 알려줘", "expect_domain": "부동산", "expect_channel": "email"},
    {"input": "카카오 백엔드 공고 중 경력 3년 이하 포지션 나오면 매시간 체크해줘", "expect_domain": "채용", "expect_cron": "0 * * * *"},

    # 영어 입력
    {"input": "notify me when gangnam 2-room rent changes", "expect_domain": "부동산"},

    # 구어체
    {"input": "야 강남 집값 오르면 바로 알려줘ㅋㅋ", "expect_domain": "부동산"},

    # 관련 없는 입력 — info (모니터링 무관 요청)
    {"input": "파이썬 코드 짜줘", "expect_intent": "info"},

    # 애매한 숫자 표현 — confirmation 필요
    {"input": "강남 집값 좀 많이 오르면 알려줘", "expect_domain": "부동산", "expect_needs_confirm": True},
    {"input": "테슬라 주가 반토막 나면 알려줘", "expect_intent": "reject"},

    # 복수 조건
    {"input": "강남이랑 서초 둘 다 시세 알려줘", "expect_domain": "부동산"},

    # 시간 표현
    {"input": "내일 아침에 비트코인 시세 알려줘", "expect_intent": "reject"},
    {"input": "주말에는 쉬고 평일만 체크해줘", "expect_needs_confirm": True, "expect_cron": "0 9 * * 1-5"},

    # 삭제 요청
    {"input": "아까 등록한 강남 시세 알림 취소해줘", "expect_intent": "delete"},

    # 특수문자/오타
    {"input": "강남 투룸 시세!!!! 바뀌면 알려줘ㅠㅠ", "expect_domain": "부동산"},
    {"input": "테슬ㄹ라 주가 5프로 오르면", "expect_intent": "reject"},

    # 넷플릭스 구독 — 일반 질문이면 info, 모니터링 요청이면 reject
    {"input": "넷플릭스 요금 또 오르면 알려줄 수 있어?", "expect_intent": "reject"},
]


MULTI_TURN_TEST_CASES = [
    # ─── 초기 대화 흐름 시나리오 ───

    # A. 인사 → 서비스 소개 → 도메인 질문 → 구독 시작 전환
    {
        "initial_input": "안녕",
        "follow_ups": [
            {"response": "지금 구독할 수 있는 도메인은 뭐야?", "expect_strategy": "A"},
            {"response": "그러면 강남 아파트 시세 바뀌면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "부동산",
    },

    # B. 서비스 질문 → 기능 설명 → 직접 구독
    {
        "initial_input": "이거 뭐하는 프로그램이야?",
        "follow_ups": [
            {"response": "강남 투룸 시세 5% 오르면 텔레그램으로 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "부동산",
        "expect_channel": "telegram",
    },

    # C. 데이터 질문(info) → 구독 유도(create)
    {
        "initial_input": "강남 시세가 어때?",
        "follow_ups": [
            {"response": "강남 시세 바뀌면 알려줘", "expect_strategy": "A"},
            {"response": "10%", "expect_strategy": "C"},
        ],
        "expect_condition": "10.0% 이상 변동",
        "expect_domain": "부동산",
    },

    # ─── 기존 다중 턴 시나리오 ───

    # 1. 퍼센티지 응답 (Strategy C)
    {
        "initial_input": "강남 집값 좀 많이 오르면 알려줘",
        "follow_ups": [
            {"response": "4% 이상 오르면", "expect_strategy": "C"}
        ],
        "expect_condition": "4.0% 이상 변동",
        "expect_needs_confirm": False,
        "expect_domain": "부동산"
    },

    # 2. 채널 선택 (Strategy C)
    {
        "initial_input": "집값 알려줘",
        "follow_ups": [
            {"response": "텔레그램으로 보내줘", "expect_strategy": "A"}
        ],
        "expect_channel": "telegram",
        "expect_domain": "부동산"
    },

    # 3. 지역 변경 (Strategy A)
    {
        "initial_input": "강남 투룸 시세 바뀌면 알려줘",
        "follow_ups": [
            {"response": "강남 말고 서초로 바꿔줘", "expect_strategy": "A"}
        ],
        "expect_domain": "부동산",
    },

    # 4. 퍼센티지 + 채널 변경 (C → A)
    {
        "initial_input": "강남 집값 오르면 알려줘",
        "follow_ups": [
            {"response": "5% 이상", "expect_strategy": "C"},
            {"response": "디스코드 말고 이메일로 바꿔줘", "expect_strategy": "A"}
        ],
        "expect_condition": "5.0% 이상 변동",
        "expect_channel": "email",
    },

    # 5. Yes 확인 (Strategy C)
    {
        "initial_input": "아까 등록한 강남 시세 알림 취소해줘",
        "follow_ups": [
            {"response": "네 맞아요", "expect_strategy": "C"}
        ],
        "expect_needs_confirm": False
    },

    # 6. 금액 조건 (Strategy A)
    {
        "initial_input": "마포구 원룸 월세 알려줘",
        "follow_ups": [
            {"response": "30만원 이하로 내려가면", "expect_strategy": "A"}
        ],
        "expect_domain": "부동산",
    },

    # 7. 최대 턴 초과 강제 완료
    {
        "initial_input": "집값 알려줘",
        "follow_ups": [
            {"response": "음...", "expect_strategy": "A"},
            {"response": "그냥...", "expect_strategy": "A"},
            {"response": "아무거나", "expect_strategy": "A"},
        ],
        "expect_needs_confirm": False
    },

    # 8. 복합 필드 업데이트 (Strategy A)
    {
        "initial_input": "채용공고 알려줘",
        "follow_ups": [
            {
                "response": "백엔드 포지션에 경력 3년 이상, 네이버 채용공고면 매시간 체크해주고 이메일로 알려줘",
                "expect_strategy": "A"
            }
        ],
        "expect_domain": "채용",
        "expect_channel": "email",
    },

    # ─── 추가 초기 대화 시나리오 ───

    # D. 채용 도메인 탐색 → 구독
    {
        "initial_input": "뭐 할 수 있어?",
        "follow_ups": [
            {"response": "채용 공고 알림 받을 수 있어?", "expect_strategy": "A"},
            {"response": "백엔드 채용공고 새로 뜨면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
    },

    # E. 도메인 질문 → 경매 구독
    {
        "initial_input": "어떤 도메인 있어?",
        "follow_ups": [
            {"response": "경매 물건 알림 받고 싶어", "expect_strategy": "A"},
        ],
        "expect_domain": "경매",
    },
]
