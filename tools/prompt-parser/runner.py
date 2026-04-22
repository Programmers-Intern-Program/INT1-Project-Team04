import json
import os
import re
from openai import OpenAI
from dotenv import load_dotenv
from prompt import SYSTEM_PROMPT, build_prompt
from test_cases import TEST_CASES

# .env 로드
load_dotenv()

# OpenAI 호환 클라이언트 (Zhipu / GLM)
client = OpenAI(
    api_key=os.getenv("ZAI_API_KEY"),
    base_url="https://api.z.ai/api/coding/paas/v4"
)

def extract_json(raw: str) -> str:
    """AI 응답에서 JSON 문자열을 추출한다."""
    if not raw:
        return '[]'
    # 코드 블록 제거
    if "```" in raw:
        raw = re.sub(r'^.*?```(?:json)?\s*', '', raw, flags=re.DOTALL)
        raw = re.sub(r'\s*```.*$', '', raw, flags=re.DOTALL)
    raw = raw.strip()
    if not raw:
        return '[]'
    # 이미 유효한 JSON이면 그대로 반환
    try:
        parsed = json.loads(raw)
        return raw if isinstance(parsed, (list, dict)) else '[]'
    except json.JSONDecodeError:
        pass
    # [가 {보다 먼저 나오면 배열로 추출 (복수 대상)
    bracket_pos = raw.find('[')
    brace_pos = raw.find('{')
    if bracket_pos != -1 and (brace_pos == -1 or bracket_pos < brace_pos):
        match = re.search(r'\[.*\]', raw, flags=re.DOTALL)
        if match:
            return match.group(0)
    # 단일 객체 { ... } 추출 → 배열로 감싸서 반환
    match = re.search(r'\{.*\}', raw, flags=re.DOTALL)
    if match:
        return '[' + match.group(0) + ']'
    print(f"  [DEBUG] JSON 추출 실패, 원본 응답: {raw[:300]}")
    return '[]'

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

            # confidence 소수점 둘째 자리 보정
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


if __name__ == "__main__":
    run_tests()
