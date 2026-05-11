import json
import os
import re
import copy
import uuid
from dataclasses import dataclass, field
from typing import List, Optional, Dict, Any
from openai import OpenAI
from dotenv import load_dotenv
from prompt import (
    SYSTEM_PROMPT, build_prompt,
    CONTINUE_SYSTEM_PROMPT, build_continue_prompt,
)
from test_cases import TEST_CASES, MULTI_TURN_TEST_CASES

# .env 로드
load_dotenv()


def safe_print(*args, **kwargs):
    try:
        print(*args, **kwargs)
    except Exception:
        try:
            msg = " ".join(str(a).encode("cp949", errors="replace").decode("cp949") for a in args)
            print(msg, **kwargs)
        except Exception:
            pass


# OpenAI 호환 클라이언트 (Zhipu / GLM)
client = OpenAI(
    api_key=os.getenv("API_KEY"),
    base_url="https://aigw.alpha.grepp.co/v1"
)

# ─── JSON 추출 ───────────────────────────────────────────────

def extract_json(raw: str) -> str:
    """AI 응답에서 JSON 문자열을 추출한다."""
    if not raw:
        return '[]'
    if "```" in raw:
        raw = re.sub(r'^.*?```(?:json)?\s*', '', raw, flags=re.DOTALL)
        raw = re.sub(r'\s*```.*$', '', raw, flags=re.DOTALL)
    raw = raw.strip()
    if not raw:
        return '[]'
    try:
        parsed = json.loads(raw)
        return raw if isinstance(parsed, (list, dict)) else '[]'
    except json.JSONDecodeError:
        pass
    bracket_pos = raw.find('[')
    brace_pos = raw.find('{')
    if bracket_pos != -1 and (brace_pos == -1 or bracket_pos < brace_pos):
        match = re.search(r'\[.*\]', raw, flags=re.DOTALL)
        if match:
            return match.group(0)
    match = re.search(r'\{.*\}', raw, flags=re.DOTALL)
    if match:
        return '[' + match.group(0) + ']'
    safe_print(f"  [DEBUG] JSON 추출 실패, 원본 응답: {raw[:300]}")
    return '[]'

# ─── API 호출 ─────────────────────────────────────────────────

def call_api(user_input: str) -> str:
    response = client.chat.completions.create(
        model="glm-5.1",
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_prompt(user_input)}
        ],
        max_tokens=2048,
        temperature=0.3
    )
    content = response.choices[0].message.content
    return content.strip() if content else ''


def call_api_with_history(messages: list) -> str:
    """전체 대화 히스토리를 API에 전달 (Strategy A)."""
    response = client.chat.completions.create(
        model="glm-5.1",
        messages=messages,
        max_tokens=2048,
        temperature=0.3
    )
    content = response.choices[0].message.content
    return content.strip() if content else ''

# ─── 단일 턴 파싱 ─────────────────────────────────────────────

def parse_task(user_input: str, max_retries: int = 2) -> list:
    last_error = None
    for attempt in range(max_retries + 1):
        try:
            raw = call_api(user_input)
            cleaned = extract_json(raw)
            result = json.loads(cleaned)
            if isinstance(result, list):
                return result
            return [result]
        except (json.JSONDecodeError, Exception) as e:
            last_error = e
            safe_print(f"  [DEBUG] 시도 {attempt+1} 실패: {e}")
            if attempt == 0:
                safe_print(f"  [DEBUG] 원본 응답: {raw[:500] if raw else '(empty)'}")
            if attempt < max_retries:
                continue
    raise last_error

# ─── 세션 관리 ─────────────────────────────────────────────────

@dataclass
class ConversationSession:
    """다중 턴 대화 상태를 관리한다."""
    session_id: str
    original_input: str
    messages: List[Dict[str, str]] = field(default_factory=list)
    current_result: List[Dict[str, Any]] = field(default_factory=list)
    turn_count: int = 0
    max_turns: int = 3
    is_complete: bool = False

    def update_result(self, new_result: List[Dict[str, Any]]):
        self.current_result = copy.deepcopy(new_result)
        all_tasks_non_create = all(
            task.get("intent") in ("info", "reject")
            for task in self.current_result
        )
        if all_tasks_non_create:
            self.is_complete = False
        else:
            self.is_complete = all(
                not task.get("metadata", {}).get("needs_confirmation", False)
                for task in self.current_result
            )

    def get_first_confirmation_question(self) -> Optional[str]:
        for task in self.current_result:
            meta = task.get("metadata", {})
            if meta.get("needs_confirmation") and meta.get("confirmation_question"):
                return meta["confirmation_question"]
        return None


def start_session(user_input: str) -> ConversationSession:
    result = parse_task(user_input)
    all_tasks_non_create = all(
        task.get("intent") in ("info", "reject")
        for task in result
    )
    if all_tasks_non_create:
        is_complete = False
    else:
        is_complete = all(
            not task.get("metadata", {}).get("needs_confirmation", False)
            for task in result
        )
    session = ConversationSession(
        session_id=str(uuid.uuid4()),
        original_input=user_input,
        current_result=result,
        is_complete=is_complete,
    )
    return session

# ─── Strategy C: 프로그래밍 방식 병합 ─────────────────────────

_PERCENT_PATTERN = re.compile(r'(\d+(?:\.\d+)?)\s*(?:%|프로|퍼센트)')
_CHANNEL_MAP = {
    "discord": "discord", "디스코드": "discord", "디코": "discord",
    "email": "email", "이메일": "email", "메일": "email",
    "telegram": "telegram", "텔레그램": "telegram", "텔레": "telegram",
}
_YES_WORDS = {"네", "예", "응", "그래", "맞아", "맞습니다", "yes", "y", "좋아", "좋아요", "그래요"}
_NO_WORDS = {"아니", "아니요", "아냐", "no", "n", "싫어", "괜찮아", "됐어"}
_DOMAIN_ALIASES = {
    "부동산": "부동산", "시세": "부동산", "집값": "부동산", "아파트": "부동산", "부동산시세": "부동산",
    "법률": "법률", "법령": "법률", "법": "법률",
    "채용": "채용", "채용공고": "채용", "공고": "채용", "jobs": "채용",
    "경매": "경매", "공매": "경매",
}
_DOMAIN_KEYWORDS = [
    (["부동산", "시세", "집값", "아파트", "월세", "전세", "land", "real", "naver"], "부동산"),
    (["법률", "법령", "판례", "law", "legal"], "법률"),
    (["채용", "공고", "job", "career", "recruit", "worknet"], "채용"),
    (["경매", "공매", "auction", "court"], "경매"),
]


def _normalize_domain(domain_name: str) -> str:
    if domain_name in _DOMAIN_ALIASES:
        return _DOMAIN_ALIASES[domain_name]
    domain_lower = domain_name.lower()
    for keywords, canonical in _DOMAIN_KEYWORDS:
        if any(kw in domain_lower for kw in keywords):
            return canonical
    return domain_name


def _detect_percentage(text: str) -> Optional[float]:
    match = _PERCENT_PATTERN.search(text)
    return float(match.group(1)) if match else None


def _detect_channel(text: str) -> Optional[str]:
    text_lower = text.lower()
    found = set()
    for keyword, channel in _CHANNEL_MAP.items():
        if keyword in text_lower:
            found.add(channel)
    if len(found) == 1:
        return found.pop()
    return None


def _detect_boolean(text: str) -> Optional[bool]:
    text_lower = text.lower().strip()
    if any(text_lower.startswith(w) for w in _YES_WORDS):
        return True
    if any(text_lower.startswith(w) for w in _NO_WORDS):
        return False
    return None


def _is_threshold_question(question: str) -> bool:
    keywords = ["%", "프로", "퍼센트", "얼마나", "몇", "수치", "기준", "변동", "오르", "내리"]
    return any(kw in question for kw in keywords)


def _is_channel_question(question: str) -> bool:
    keywords = ["채널", "채팅", "디스코드", "이메일", "텔레그램", "어디로", "알림을 받을"]
    return any(kw in question for kw in keywords)


def _is_boolean_question(question: str) -> bool:
    patterns = ["하시겠어요?", "할까요?", "그대로", "확인해드릴까요", "맞나요?", "보여드릴까요?"]
    return any(p in question for p in patterns)


def try_programmatic_merge(
        current_result: List[Dict[str, Any]],
        user_response: str
) -> Optional[List[Dict[str, Any]]]:
    """
    AI 없이 사용자 응답을 기존 결과에 병합한다.
    성공하면 업데이트된 결과를, 실패하면 None을 반환 (Strategy A 폴백).
    """
    updated = copy.deepcopy(current_result)
    any_change = False

    # 퍼센티지 감지
    pct = _detect_percentage(user_response)
    if pct is not None:
        for task in updated:
            meta = task.get("metadata", {})
            if meta.get("needs_confirmation"):
                question = meta.get("confirmation_question", "")
                if _is_threshold_question(question):
                    task["condition"] = f"{pct}% 이상 변동"
                    meta["needs_confirmation"] = False
                    meta["confirmation_question"] = ""
                    meta["confidence"] = min(1.0, meta.get("confidence", 0.5) + 0.1)
                    any_change = True

        if not any_change:
            for task in updated:
                if task.get("intent") == "create":
                    meta = task.get("metadata", {})
                    if not meta.get("needs_confirmation") and not task.get("condition"):
                        task["condition"] = f"{pct}% 이상 변동"
                        meta["confidence"] = min(1.0, meta.get("confidence", 0.5) + 0.1)
                        any_change = True
                        break
                    elif meta.get("needs_confirmation"):
                        task["condition"] = f"{pct}% 이상 변동"
                        meta["needs_confirmation"] = False
                        meta["confirmation_question"] = ""
                        meta["confidence"] = min(1.0, meta.get("confidence", 0.5) + 0.1)
                        any_change = True
                        break

    # 채널 감지
    channel = _detect_channel(user_response)
    if channel is not None:
        for task in updated:
            meta = task.get("metadata", {})
            if meta.get("needs_confirmation"):
                question = meta.get("confirmation_question", "")
                if _is_channel_question(question):
                    task["channel"] = channel
                    meta["needs_confirmation"] = False
                    meta["confirmation_question"] = ""
                    meta["confidence"] = min(1.0, meta.get("confidence", 0.5) + 0.1)
                    any_change = True

        if not any_change:
            for task in updated:
                if task.get("intent") == "create":
                    task["channel"] = channel
                    any_change = True
                    break

    # Yes/No 감지
    if not any_change:
        bool_val = _detect_boolean(user_response)
        if bool_val is not None:
            for task in updated:
                meta = task.get("metadata", {})
                if meta.get("needs_confirmation"):
                    question = meta.get("confirmation_question", "")
                    if _is_boolean_question(question):
                        if bool_val:
                            meta["needs_confirmation"] = False
                            meta["confirmation_question"] = ""
                            any_change = True
                        else:
                            return None  # 거부 → AI 폴백

    return updated if any_change else None

def _fix_intent_for_monitoring(user_response: str, result: list):
    _MONITOR_KW = {"알려줘", "알림", "바뀌면", "변하면", "체크해줘", "모니터링", "구독", "오르면", "내리면", "뜨면", "나오면", "넘으면", "늘면"}
    _DOMAIN_KW = {
        "부동산": ["시세", "집값", "부동산", "아파트", "월세", "전세", "원룸", "투룸"],
        "채용": ["채용", "공고", "채용공고"],
        "법률": ["법률", "법령", "판례"],
        "경매": ["경매", "공매"],
    }
    has_monitor = any(kw in user_response for kw in _MONITOR_KW)
    if not has_monitor:
        return
    for task in result:
        if task.get("intent") in ("info", "reject"):
            detected_domain = None
            for domain, keywords in _DOMAIN_KW.items():
                if any(kw in user_response for kw in keywords):
                    detected_domain = domain
                    break
            if detected_domain:
                task["intent"] = "create"
                task["domain_name"] = detected_domain
                meta = task.get("metadata", {})
                if not task.get("condition"):
                    task["condition"] = ""
                if not task.get("cron_expr"):
                    task["cron_expr"] = "0 9 * * *"
                if not task.get("channel"):
                    task["channel"] = "discord"
                if not task.get("api_type"):
                    task["api_type"] = "crawl"
                meta["needs_confirmation"] = True
                if not meta.get("confirmation_question"):
                    meta["confirmation_question"] = "모니터링 조건을 구체적으로 알려주시겠어요?"


# ─── Strategy A: 대화 히스토리 기반 병합 ───────────────────────

def _merge_via_ai(session: ConversationSession, user_response: str) -> list:
    """AI에 전체 대화 히스토리를 전달하여 결과를 업데이트한다."""
    history = [
        {"role": "system", "content": CONTINUE_SYSTEM_PROMPT},
        {"role": "user", "content": build_prompt(session.original_input)},
        {"role": "assistant", "content": json.dumps(session.current_result, ensure_ascii=False)},
    ]
    for msg in session.messages:
        history.append(msg)
    history.append({"role": "user", "content": user_response})

    raw = call_api_with_history(history)
    cleaned = extract_json(raw)
    result = json.loads(cleaned)
    if isinstance(result, dict):
        result = [result]
    return result

# ─── 다중 턴 오케스트레이터 ───────────────────────────────────

def continue_task(session: ConversationSession, user_response: str) -> ConversationSession:
    """
    사용자의 후속 응답을 처리한다.
    Strategy C → 실패시 Strategy A 폴백.
    """
    session.turn_count += 1
    if session.turn_count >= session.max_turns:
        for task in session.current_result:
            meta = task.get("metadata", {})
            if meta.get("needs_confirmation"):
                meta["needs_confirmation"] = False
                meta["confirmation_question"] = ""
        session.is_complete = True
        return session

    # Strategy C
    merged = try_programmatic_merge(session.current_result, user_response)
    if merged is not None:
        session.update_result(merged)
        session.messages.append({"role": "user", "content": user_response})
        session.messages.append({"role": "assistant", "content": json.dumps(merged, ensure_ascii=False)})
        return session

    # Strategy A
    result = _merge_via_ai(session, user_response)

    _fix_intent_for_monitoring(user_response, result)

    session.update_result(result)
    session.messages.append({"role": "user", "content": user_response})
    session.messages.append({"role": "assistant", "content": json.dumps(result, ensure_ascii=False)})

    return session

# ─── 단일 턴 테스트 러너 ──────────────────────────────────────

def run_tests():
    results = []
    pass_count = 0
    fail_count = 0

    for i, case in enumerate(TEST_CASES):
        safe_print(f"\n[{i+1}/{len(TEST_CASES)}] 입력: {case['input']}")

        results_list = None
        passed = False
        try:
            results_list = parse_task(case['input'])
            primary = results_list[0]
            metadata = primary.get('metadata', {})

            confidence = metadata.get('confidence')
            if confidence is not None:
                metadata['confidence'] = round(float(confidence), 2)

            passed = True

            if 'expect_intent' in case:
                expected = case['expect_intent']
                actual = primary.get('intent')
                passed = actual == expected
                if not passed:
                    safe_print(f"  [DEBUG] intent 불일치: expected={expected}, actual={actual}")

            if passed and 'expect_domain' in case:
                expected = case['expect_domain']
                actual = _normalize_domain(primary.get('domain_name', ''))
                passed = actual == expected
                if not passed:
                    safe_print(f"  [DEBUG] domain 불일치: expected={expected}, actual={primary.get('domain_name')}")

            if passed and 'expect_channel' in case:
                passed = primary.get('channel') == case['expect_channel']

            if passed and 'expect_cron' in case:
                passed = primary.get('cron_expr') == case['expect_cron']

            if passed and case.get('expect_needs_confirm'):
                passed = metadata.get('needs_confirmation') == True

        except Exception as e:
            safe_print(f"  파싱 오류: {e}")
            passed = False

        status = "PASS" if passed else "FAIL"
        if passed:
            pass_count += 1
        else:
            fail_count += 1

        if results_list:
            primary = results_list[0]
            metadata = primary.get('metadata', {})
            needs_confirm = " (확인 필요)" if metadata.get('needs_confirmation') else ""
            safe_print(f"  {status}{needs_confirm}")
            if len(results_list) > 1:
                safe_print(f"  ({len(results_list)}개 태스크 분리)")
            for idx, r in enumerate(results_list):
                prefix = f"  [{idx+1}] " if len(results_list) > 1 else "  "
                m = r.get('metadata', {})
                safe_print(f"{prefix}intent   : {r.get('intent')}")
                safe_print(f"{prefix}domain   : {r.get('domain_name')}")
                safe_print(f"{prefix}query    : {r.get('query')}")
                safe_print(f"{prefix}condition: {r.get('condition')}")
                safe_print(f"{prefix}cron_expr: {r.get('cron_expr')}")
                safe_print(f"{prefix}channel  : {r.get('channel')}")
                safe_print(f"{prefix}api_type : {r.get('api_type')}")
                safe_print(f"{prefix}confidence: {m.get('confidence')}")
                safe_print(f"{prefix}needs_confirmation: {m.get('needs_confirmation')}")
                if m.get('confirmation_question'):
                    safe_print(f"{prefix}confirmation_question: {m.get('confirmation_question')}")
        else:
            safe_print(f"  {status} (결과 없음)")

        results.append({
            "input": case['input'],
            "output": results_list,
            "passed": passed
        })

    safe_print(f"\n========================================")
    safe_print(f"결과: {pass_count}개 통과 / {fail_count}개 실패")
    safe_print(f"통과율: {pass_count / len(TEST_CASES) * 100:.1f}%")

    with open("results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    safe_print("results.json 저장 완료")

# ─── 다중 턴 테스트 러너 ──────────────────────────────────────

def run_multi_turn_tests():
    results = []
    pass_count = 0
    fail_count = 0

    for i, case in enumerate(MULTI_TURN_TEST_CASES):
        safe_print(f"\n[{i+1}/{len(MULTI_TURN_TEST_CASES)}] 초기 입력: {case['initial_input']}")

        session = None
        final = None
        try:
            session = start_session(case['initial_input'])
            primary = session.current_result[0]
            meta = primary.get('metadata', {})
            safe_print(f"  1차 결과: intent={primary.get('intent')}, needs_confirmation={meta.get('needs_confirmation')}")
            if meta.get('confirmation_question'):
                safe_print(f"  AI 질문: {meta.get('confirmation_question')}")

            for turn_idx, follow_up in enumerate(case.get('follow_ups', [])):
                response = follow_up['response']
                expected_strategy = follow_up.get('expect_strategy', 'A')
                safe_print(f"  [{turn_idx+2}턴] 사용자: {response} (예상 전략: {expected_strategy})")
                try:
                    session = continue_task(session, response)
                except Exception as e:
                    safe_print(f"  [턴 {turn_idx+2} 오류: {e}")
                    break

        except Exception as e:
            safe_print(f"  파싱 오류: {e}")

        if session and session.current_result:
            final = session.current_result

        passed = False
        if final:
            primary = final[0]
            meta = primary.get('metadata', {})

            passed = True

            if 'expect_condition' in case:
                passed = passed and primary.get('condition') == case['expect_condition']

            if passed and 'expect_channel' in case:
                passed = passed and primary.get('channel') == case['expect_channel']

            if passed and 'expect_needs_confirm' in case:
                passed = passed and meta.get('needs_confirmation') == case['expect_needs_confirm']

            if passed and 'expect_domain' in case:
                passed = passed and _normalize_domain(primary.get('domain_name', '')) == case['expect_domain']

            status = "PASS" if passed else "FAIL"
            safe_print(f"  {status} (총 {session.turn_count}턴)")
            safe_print(f"    condition: {primary.get('condition')}")
            safe_print(f"    channel: {primary.get('channel')}")
            safe_print(f"    needs_confirmation: {meta.get('needs_confirmation')}")
        else:
            safe_print(f"  FAIL (결과 없음)")

        if passed:
            pass_count += 1
        else:
            fail_count += 1

        results.append({
            "initial_input": case['initial_input'],
            "follow_ups": case.get('follow_ups', []),
            "output": final,
            "turns": session.turn_count if session else 0,
            "passed": passed
        })

    safe_print(f"\n========================================")
    safe_print(f"다중 턴 결과: {pass_count}개 통과 / {fail_count}개 실패")
    if len(MULTI_TURN_TEST_CASES) > 0:
        safe_print(f"통과율: {pass_count / len(MULTI_TURN_TEST_CASES) * 100:.1f}%")

    with open("multi_turn_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    safe_print("multi_turn_results.json 저장 완료")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--multi":
        run_multi_turn_tests()
    else:
        run_tests()
