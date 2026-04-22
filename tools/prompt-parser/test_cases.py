TEST_CASES = [
    # 명확한 케이스 — 지원 도메인
    {"input": "강남 투룸 시세 바뀌면 알려줘", "expect_domain": "부동산", "expect_cron": "0 9 * * *"},
    {"input": "넥슨 채용공고에 Java 3년 이상 뜨면 알려줘", "expect_domain": "채용"},

    # 미지원 도메인 — reject
    {"input": "테슬라 주가 5% 이상 오르면 디스코드로 알려줘", "expect_intent": "reject"},

    # 모호한 케이스 — needs_confirmation 필요
    {"input": "집값 알려줘", "expect_domain": "부동산", "expect_needs_confirm": True},
    {"input": "뭔가 바뀌면 알려줘", "expect_intent": "reject"},
    {"input": "주식", "expect_intent": "reject"},

    # 복합 조건 — 지원 도메인
    {"input": "마포구 원룸 월세 50만원 이하로 내려가면 이메일로 알려줘", "expect_domain": "부동산", "expect_channel": "email"},
    {"input": "카카오 백엔드 공고 중 경력 3년 이하 포지션 나오면 매시간 체크해줘", "expect_domain": "채용", "expect_cron": "0 * * * *"},

    # 미지원 도메인 — reject
    {"input": "비트코인 1억 넘으면 알려줘", "expect_intent": "reject"},

    # 영어 입력
    {"input": "notify me when gangnam 2-room rent changes", "expect_domain": "부동산"},

    # 구어체
    {"input": "야 강남 집값 오르면 바로 알려줘ㅋㅋ", "expect_domain": "부동산"},
    {"input": "넷플릭스 요금 또 오르면 알려줄 수 있어?", "expect_intent": "reject"},

    # 관련 없는 입력 — reject
    {"input": "오늘 점심 뭐 먹지", "expect_intent": "reject"},
    {"input": "파이썬 코드 짜줘", "expect_intent": "reject"},

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
]
