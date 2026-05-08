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

# OpenAI 호환 클라이언트 (Zhipu / GLM)
client = OpenAI(
    api_key=os.getenv("ZAI_API_KEY"),
    base_url="https://api.z.ai/api/coding/paas/v4"
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
    print(f"  [DEBUG] JSON 추출 실패, 원본 응답: {raw[:300]}")
    return '[]'

# ─── API 호출 ─────────────────────────────────────────────────

def call_api(user_input: str) -> str:
    response = client.chat.completions.create(
        model="glm-4.5",
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
        model="glm-4.5",
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
            print(f"  [DEBUG] 시도 {attempt+1} 실패: {e}")
            if attempt == 0:
                print(f"  [DEBUG] 원본 응답: {raw[:500] if raw else '(empty)'}")
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
    """최초 입력을 파싱하고 세션을 생성한다."""
    result = parse_task(user_input)
    session = ConversationSession(
        session_id=str(uuid.uuid4()),
        original_input=user_input,
        current_result=result,
        is_complete=all(
            not task.get("metadata", {}).get("needs_confirmation", False)
            for task in result
        )
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


def _detect_percentage(text: str) -> Optional[float]:
    match = _PERCENT_PATTERN.search(text)
    return float(match.group(1)) if match else None


def _detect_channel(text: str) -> Optional[str]:
    text_lower = text.lower()
    for keyword, channel in _CHANNEL_MAP.items():
        if keyword in text_lower:
            return channel
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
    if session.is_complete:
        return session

    session.turn_count += 1
    if session.turn_count > session.max_turns:
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
        print(f"\n[{i+1}/{len(TEST_CASES)}] 입력: {case['input']}")

        try:
            results_list = parse_task(case['input'])
            primary = results_list[0]
            metadata = primary.get('metadata', {})

            confidence = metadata.get('confidence')
            if confidence is not None:
                metadata['confidence'] = round(float(confidence), 2)

            passed = True

            if 'expect_intent' in case:
                passed = primary.get('intent') == case['expect_intent']

            if passed and 'expect_domain' in case:
                passed = primary.get('domain_name') == case['expect_domain']

            if passed and 'expect_channel' in case:
                passed = primary.get('channel') == case['expect_channel']

            if passed and 'expect_cron' in case:
                passed = primary.get('cron_expr') == case['expect_cron']

            if passed and case.get('expect_needs_confirm'):
                passed = metadata.get('needs_confirmation') == True

            status = "PASS" if passed else "FAIL"

            if passed:
                pass_count += 1
            else:
                fail_count += 1

            needs_confirm = " (확인 필요)" if metadata.get('needs_confirmation') else ""
            print(f"  {status}{needs_confirm}")
            if len(results_list) > 1:
                print(f"  ({len(results_list)}개 태스크 분리)")
            for idx, r in enumerate(results_list):
                prefix = f"  [{idx+1}] " if len(results_list) > 1 else "  "
                m = r.get('metadata', {})
                print(f"{prefix}intent   : {r.get('intent')}")
                print(f"{prefix}domain   : {r.get('domain_name')}")
                print(f"{prefix}query    : {r.get('query')}")
                print(f"{prefix}condition: {r.get('condition')}")
                print(f"{prefix}cron_expr: {r.get('cron_expr')}")
                print(f"{prefix}channel  : {r.get('channel')}")
                print(f"{prefix}api_type : {r.get('api_type')}")
                print(f"{prefix}confidence: {m.get('confidence')}")
                print(f"{prefix}needs_confirmation: {m.get('needs_confirmation')}")
                if m.get('confirmation_question'):
                    print(f"{prefix}confirmation_question: {m.get('confirmation_question')}")

            results.append({
                "input": case['input'],
                "output": results_list,
                "passed": passed
            })

        except Exception as e:
            print(f"  오류 발생: {e}")
            fail_count += 1
            results.append({
                "input": case['input'],
                "output": None,
                "passed": False
            })

    print(f"\n========================================")
    print(f"결과: {pass_count}개 통과 / {fail_count}개 실패")
    print(f"통과율: {pass_count / len(TEST_CASES) * 100:.1f}%")

    with open("results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("results.json 저장 완료")

# ─── 다중 턴 테스트 러너 ──────────────────────────────────────

def run_multi_turn_tests():
    results = []
    pass_count = 0
    fail_count = 0

    for i, case in enumerate(MULTI_TURN_TEST_CASES):
        print(f"\n[{i+1}/{len(MULTI_TURN_TEST_CASES)}] 초기 입력: {case['initial_input']}")

        try:
            session = start_session(case['initial_input'])
            primary = session.current_result[0]
            meta = primary.get('metadata', {})
            print(f"  1차 결과: intent={primary.get('intent')}, needs_confirmation={meta.get('needs_confirmation')}")
            if meta.get('confirmation_question'):
                print(f"  AI 질문: {meta.get('confirmation_question')}")

            for turn_idx, follow_up in enumerate(case.get('follow_ups', [])):
                response = follow_up['response']
                expected_strategy = follow_up.get('expect_strategy', 'A')
                print(f"  [{turn_idx+2}턴] 사용자: {response} (예상 전략: {expected_strategy})")
                session = continue_task(session, response)

            # 최종 결과 검증
            final = session.current_result
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
                passed = passed and primary.get('domain_name') == case['expect_domain']

            status = "PASS" if passed else "FAIL"
            print(f"  {status} (총 {session.turn_count}턴)")
            print(f"    condition: {primary.get('condition')}")
            print(f"    channel: {primary.get('channel')}")
            print(f"    needs_confirmation: {meta.get('needs_confirmation')}")

            if passed:
                pass_count += 1
            else:
                fail_count += 1

            results.append({
                "initial_input": case['initial_input'],
                "follow_ups": case.get('follow_ups', []),
                "output": final,
                "turns": session.turn_count,
                "passed": passed
            })

        except Exception as e:
            print(f"  오류 발생: {e}")
            fail_count += 1
            results.append({
                "initial_input": case['initial_input'],
                "output": None,
                "passed": False
            })

    print(f"\n========================================")
    print(f"다중 턴 결과: {pass_count}개 통과 / {fail_count}개 실패")
    if len(MULTI_TURN_TEST_CASES) > 0:
        print(f"통과율: {pass_count / len(MULTI_TURN_TEST_CASES) * 100:.1f}%")

    with open("multi_turn_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("multi_turn_results.json 저장 완료")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--multi":
        run_multi_turn_tests()
    else:
        run_tests()
