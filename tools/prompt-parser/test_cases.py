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

    # ====================================================================
    # 채용 (Recruitment) 도메인 테스트
    # ====================================================================

    # 기본 채용 모니터링
    {"input": "백엔드 채용공고 새로 뜨면 알려줘", "expect_domain": "채용", "expect_intent": "create"},
    {"input": "네이버 프론트엔드 공고 나오면 알려줘", "expect_domain": "채용", "expect_intent": "create"},
    {"input": "공공기관 데이터 분석 채용공고 텔레그램으로 알려줘", "expect_domain": "채용", "expect_channel": "telegram"},
    {"input": "워크넷 백엔드 공고 매시간 체크해줘", "expect_domain": "채용", "expect_cron": "0 * * * *"},

    # 채용 조건 포함
    {"input": "카카오 경력 5년 이상 공고 3건 이상 늘면 알려줘", "expect_domain": "채용", "expect_intent": "create"},
    {"input": "라인 서버 개발 공고 중 신입 포지션 나오면 이메일로 알려줘", "expect_domain": "채용", "expect_channel": "email"},

    # 채용 모호한 케이스
    {"input": "채용공고 알려줘", "expect_domain": "채용", "expect_intent": "create", "expect_needs_confirm": True},
    {"input": "백엔드 공고 뜨면 알려줘", "expect_domain": "채용", "expect_intent": "create"},

    # 채용 복수 대상
    {"input": "네이버랑 카카오 채용공고 둘 다 알려줘", "expect_domain": "채용", "expect_intent": "create"},

    # 채용 구어체/특수문자
    {"input": "공고 뜨면 바로 알려줘ㅋㅋ 백엔드!!", "expect_domain": "채용"},

    # 채용 삭제/수정
    {"input": "아까 등록한 백엔드 채용 알림 취소해줘", "expect_intent": "delete"},
    {"input": "채용공고 알림 평일만 체크로 바꿔줘", "expect_intent": "modify"},

    # 채용 info 경계
    {"input": "요즘 백엔드 채용 시장 어때?", "expect_intent": "info"},
    {"input": "네이버 채용공고 많아?", "expect_intent": "info"},

    # ====================================================================
    # 경매 (Auction) 도메인 테스트
    # ====================================================================

    # 기본 경매 모니터링
    {"input": "서울 강남 경매 물건 나오면 알려줘", "expect_domain": "경매", "expect_intent": "create"},
    {"input": "부산 해운대 공매 물건 알려줘", "expect_domain": "경매", "expect_intent": "create"},
    {"input": "경매 감정가 5억 이하인 물건 나오면 알려줘", "expect_domain": "경매", "expect_intent": "create"},
    {"input": "서울 아파트 경매 물건 텔레그램으로 알려줘", "expect_domain": "경매", "expect_channel": "telegram"},

    # 경매 조건 포함
    {"input": "경매 물건 중 감정가 3억 이하 서울 아파트 나오면 이메일로 알려줘", "expect_domain": "경매", "expect_channel": "email"},
    {"input": "공매 토지 물건 새로 등록되면 매일 알려줘", "expect_domain": "경매", "expect_cron": "0 9 * * *"},

    # 경매 모호한 케이스
    {"input": "경매 물건 알려줘", "expect_domain": "경매", "expect_intent": "create", "expect_needs_confirm": True},

    # 경매 복수 대상
    {"input": "서울이랑 부산 경매 물건 둘 다 알려줘", "expect_domain": "경매", "expect_intent": "create"},

    # 경매 구어체/특수문자
    {"input": "경매물건 좋은 거 나오면 알려줘!!!", "expect_domain": "경매"},

    # 경매 삭제/수정
    {"input": "등록한 경매 알림 취소해줘", "expect_intent": "delete"},
    {"input": "경매 알림 주기를 매시간으로 바꿔줘", "expect_intent": "modify"},

    # 경매 info 경계
    {"input": "요즘 경매 낙찰가 어때?", "expect_intent": "info"},
    {"input": "경매 물건 많아?", "expect_intent": "info"},

    # ====================================================================
    # 법률 (Law) 도메인 테스트
    # ====================================================================

    # 기본 법률 모니터링
    {"input": "개인정보보호법 개정되면 알려줘", "expect_domain": "법률", "expect_intent": "create"},
    {"input": "근로기준법 관련 판례 새로 나오면 알려줘", "expect_domain": "법률", "expect_intent": "create"},
    {"input": "전세권 관련 법령 변경 텔레그램으로 알려줘", "expect_domain": "법률", "expect_channel": "telegram"},
    {"input": "부동산 세법 개정되면 이메일로 알려줘", "expect_domain": "법률", "expect_channel": "email"},

    # 법률 조건 포함
    {"input": "주택임대차보호법 개정 안 나오면 매일 체크해줘", "expect_domain": "법률", "expect_cron": "0 9 * * *"},
    {"input": "산업안전보건법 관련 대법원 판례 새로 나오면 알려줘", "expect_domain": "법률", "expect_intent": "create"},

    # 법률 모호한 케이스
    {"input": "법률 변경 알려줘", "expect_domain": "법률", "expect_intent": "create", "expect_needs_confirm": True},
    {"input": "법령 개정되면 알려줘", "expect_domain": "법률", "expect_intent": "create", "expect_needs_confirm": True},

    # 법률 복수 대상
    {"input": "부동산 세법이랑 임대차 법령 둘 다 알려줘", "expect_domain": "법률", "expect_intent": "create"},

    # 법률 구어체/특수문자
    {"input": "개인정보법 바뀌면 제발 알려줘ㅠㅠ", "expect_domain": "법률"},

    # 법률 삭제/수정
    {"input": "등록한 법률 개정 알림 삭제해줘", "expect_intent": "delete"},
    {"input": "법률 알림 매주로 바꿔줘", "expect_intent": "modify"},

    # 법률 info 경계
    {"input": "최근 개인정보보호법 개정 내용 어때?", "expect_intent": "info"},
    {"input": "전세 관련 법령 어떻게 돼?", "expect_intent": "info"},

    # ====================================================================
    # 데이터 조회 (info intent) — MCP 도구 연동 시나리오
    # info → FetchInfoDataAdapter.fetch() → MCP 도구 실행 → 데이터 반환
    # 구독 없이 1회성 데이터 질문
    # ====================================================================

    # --- 부동산 데이터 조회 ---
    {"input": "강남 아파트 실거래가 어때?", "expect_intent": "info", "expect_domain": "부동산"},
    {"input": "서초구 원룸 월세 시세 알려줘", "expect_intent": "info", "expect_domain": "부동산"},
    {"input": "마포구 전세가 어떻게 돼?", "expect_intent": "info", "expect_domain": "부동산"},
    {"input": "요즘 집값 오르고 있어?", "expect_intent": "info", "expect_domain": "부동산"},
    {"input": "송파구 아파트 매매가 추이 어때?", "expect_intent": "info", "expect_domain": "부동산"},

    # --- 채용 데이터 조회 ---
    {"input": "네이버 채용공고 많아?", "expect_intent": "info", "expect_domain": "채용"},
    {"input": "요즘 백엔드 개발자 시장 어때?", "expect_intent": "info", "expect_domain": "채용"},
    {"input": "카카오 채용 공고 어떤 포지션 있어?", "expect_intent": "info", "expect_domain": "채용"},
    {"input": "공공기관 데이터 분석 채용 진행중인 거 있어?", "expect_intent": "info", "expect_domain": "채용"},
    {"input": "프론트엔드 신입 채용공고 많이 떠 있어?", "expect_intent": "info", "expect_domain": "채용"},

    # --- 경매 데이터 조회 ---
    {"input": "요즘 경매 낙찰가 어때?", "expect_intent": "info", "expect_domain": "경매"},
    {"input": "서울 경매 물건 많아?", "expect_intent": "info", "expect_domain": "경매"},
    {"input": "경매 낙찰가율 어떻게 돼?", "expect_intent": "info", "expect_domain": "경매"},
    {"input": "부산 해운대 공매 물건 있어?", "expect_intent": "info", "expect_domain": "경매"},
    {"input": "최근 경매 입찰 경쟁률 어때?", "expect_intent": "info", "expect_domain": "경매"},

    # --- 법률 데이터 조회 ---
    {"input": "최근 전세보증금 관련 판례 어때?", "expect_intent": "info", "expect_domain": "법률"},
    {"input": "개인정보보호법 최근 개정 내용 알려줘", "expect_intent": "info", "expect_domain": "법률"},
    {"input": "근로기준법 관련 대법원 판례 어떻게 돼?", "expect_intent": "info", "expect_domain": "법률"},
    {"input": "주택임대차보호법 최근 바뀐 거 있어?", "expect_intent": "info", "expect_domain": "법률"},
    {"input": "부동산 세법 개정 예정인 거 있어?", "expect_intent": "info", "expect_domain": "법률"},

    # --- info vs create 경계 (데이터 조회 vs 모니터링 구독) ---
    # 조회: 현재 상태 질문 → info
    {"input": "강남 아파트 시세가 지금 어떻게 돼?", "expect_intent": "info", "expect_domain": "부동산"},
    {"input": "백엔드 채용 시장 현황 어때?", "expect_intent": "info", "expect_domain": "채용"},
    # 구독: 변화 감지 요청 → create (위쪽에 이미 있음)

    # ====================================================================
    # 도메인 간 혼합 / 경계 케이스
    # =====================================================================

    # 부동산 + 채용 혼합 (각각 분리)
    {"input": "강남 시세랑 백엔드 채용공고 둘 다 알려줘", "expect_intent": "create"},

    # 경매 + 부동산 경계 (경매가 부동산 키워드 포함)
    {"input": "아파트 경매 물건 알려줘", "expect_domain": "경매", "expect_intent": "create"},

    # 법률 + 부동산 경계 (전세 관련 법령은 법률)
    {"input": "전세보증금 관련 법 바뀌면 알려줘", "expect_domain": "법률", "expect_intent": "create"},
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

    # ====================================================================
    # 채용 (Recruitment) 다중 턴 시나리오
    # ====================================================================

    # F. 채용 모호 → 구체화 (Strategy A)
    {
        "initial_input": "채용공고 알려줘",
        "follow_ups": [
            {"response": "백엔드 포지션에 경력 3년 이상 공고만", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
    },

    # G. 채용 명확 → 채널 변경 (Strategy A)
    {
        "initial_input": "네이버 프론트엔드 공고 나오면 알려줘",
        "follow_ups": [
            {"response": "텔레그램으로 보내줘", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
        "expect_channel": "telegram",
    },

    # H. 채용 모호 → 퍼센티지 응답 (Strategy C)
    {
        "initial_input": "백엔드 공고 뜨면 알려줘",
        "follow_ups": [
            {"response": "5건 이상 늘면", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
    },

    # I. 채용 info → create 전환 (Strategy A)
    {
        "initial_input": "요즘 백엔드 채용 시장 어때?",
        "follow_ups": [
            {"response": "백엔드 채용공고 새로 뜨면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
    },

    # J. 채용 복합 조건 (Strategy A)
    {
        "initial_input": "공공기관 데이터 분석 채용공고 알려줘",
        "follow_ups": [
            {"response": "경력 무관, 서울 근무, 이메일로 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
        "expect_channel": "email",
    },

    # ====================================================================
    # 경매 (Auction) 다중 턴 시나리오
    # ====================================================================

    # K. 경매 모호 → 지역 구체화 (Strategy A)
    {
        "initial_input": "경매 물건 알려줘",
        "follow_ups": [
            {"response": "서울 강남 아파트, 감정가 5억 이하로", "expect_strategy": "A"},
        ],
        "expect_domain": "경매",
    },

    # L. 경매 명확 → 채널 변경 (Strategy A)
    {
        "initial_input": "부산 해운대 공매 물건 알려줘",
        "follow_ups": [
            {"response": "텔레그램으로 보내줘", "expect_strategy": "A"},
        ],
        "expect_domain": "경매",
        "expect_channel": "telegram",
    },

    # M. 경매 info → create 전환 (Strategy A)
    {
        "initial_input": "요즘 경매 낙찰가 어때?",
        "follow_ups": [
            {"response": "서울 경매 물건 나오면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "경매",
    },

    # N. 경매 복합 조건 (Strategy A)
    {
        "initial_input": "경매 물건 중 감정가 3억 이하 알려줘",
        "follow_ups": [
            {"response": "아파트만, 매주 체크, 이메일로 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "경매",
        "expect_channel": "email",
    },

    # ====================================================================
    # 법률 (Law) 다중 턴 시나리오
    # ====================================================================

    # O. 법률 모호 → 법령 구체화 (Strategy A)
    {
        "initial_input": "법률 변경 알려줘",
        "follow_ups": [
            {"response": "개인정보보호법이랑 주택임대차보호법", "expect_strategy": "A"},
        ],
        "expect_domain": "법률",
    },

    # P. 법률 명확 → 채널 변경 (Strategy A)
    {
        "initial_input": "개인정보보호법 개정되면 알려줘",
        "follow_ups": [
            {"response": "이메일로 보내줘", "expect_strategy": "A"},
        ],
        "expect_domain": "법률",
        "expect_channel": "email",
    },

    # Q. 법률 info → create 전환 (Strategy A)
    {
        "initial_input": "최근 전세 관련 법령 어떻게 바뀌었어?",
        "follow_ups": [
            {"response": "전세보증금 관련 법 바뀌면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "법률",
    },

    # R. 법률 복합 조건 (Strategy A)
    {
        "initial_input": "근로기준법 판례 새로 나오면 알려줘",
        "follow_ups": [
            {"response": "대법원 판례만, 텔레그램으로 매주 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "법률",
        "expect_channel": "telegram",
    },

    # ====================================================================
    # 데이터 조회 (info intent) → FetchInfoDataAdapter 연동 시나리오
    # 백엔드에서 MCP 도구로 실시간 데이터를 조회해 반환하는 흐름
    # info → follow-up → create 전환까지 검증
    # ====================================================================

    # S. 부동산 시세 조회 → 구독 전환
    {
        "initial_input": "강남 아파트 시세가 어때?",
        "follow_ups": [
            {"response": "강남 시세 바뀌면 알려줘", "expect_strategy": "A"},
            {"response": "5% 이상 오르면", "expect_strategy": "C"},
        ],
        "expect_domain": "부동산",
        "expect_condition": "5.0% 이상 변동",
    },

    # T. 채용공고 조회 → 구독 전환
    {
        "initial_input": "요즘 백엔드 채용 시장 어때?",
        "follow_ups": [
            {"response": "백엔드 채용공고 새로 뜨면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "채용",
    },

    # U. 법령 조회 → 구독 전환
    {
        "initial_input": "전세 관련 법령 어떻게 돼?",
        "follow_ups": [
            {"response": "전세보증금 관련 법 바뀌면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "법률",
    },

    # V. 경매 정보 조회 → 구독 전환
    {
        "initial_input": "요즘 경매 낙찰가 어때?",
        "follow_ups": [
            {"response": "서울 경매 물건 나오면 알려줘", "expect_strategy": "A"},
        ],
        "expect_domain": "경매",
    },
]
