import json
import sys
from test_cases import MULTI_TURN_TEST_CASES
from runner import (
    start_session, continue_task, safe_print, _normalize_domain
)

BATCH_SIZE = 3
START_IDX = 13
RESULTS_FILE = "new_multi_results.json"


def load_existing():
    try:
        with open(RESULTS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def run_multi_batch(batch_idx):
    existing = load_existing()
    done_count = len(existing)
    start = START_IDX + done_count
    end = min(start + BATCH_SIZE, len(MULTI_TURN_TEST_CASES))

    if start >= len(MULTI_TURN_TEST_CASES):
        safe_print(f"All {done_count} new multi-turn tests completed.")
        summarize(existing)
        return

    safe_print(f"\n=== Multi-turn Batch {batch_idx}: cases [{start+1}..{end}] ===")

    for i in range(start, end):
        case = MULTI_TURN_TEST_CASES[i]
        safe_print(f"\n[{i+1}/{len(MULTI_TURN_TEST_CASES)}] initial: {case['initial_input']}")

        session = None
        final = None
        try:
            session = start_session(case['initial_input'])
            primary = session.current_result[0]
            meta = primary.get('metadata', {})
            safe_print(f"  1st: intent={primary.get('intent')}, needs_confirm={meta.get('needs_confirmation')}")
            if meta.get('confirmation_question'):
                safe_print(f"  AI question: {meta.get('confirmation_question')}")

            for turn_idx, follow_up in enumerate(case.get('follow_ups', [])):
                response = follow_up['response']
                expected_strategy = follow_up.get('expect_strategy', 'A')
                safe_print(f"  [Turn {turn_idx+2}] user: {response} (expect: {expected_strategy})")
                try:
                    session = continue_task(session, response)
                except Exception as e:
                    safe_print(f"  [Turn {turn_idx+2} error: {e}")
                    break

        except Exception as e:
            safe_print(f"  parse error: {e}")

        if session and session.current_result:
            final = session.current_result

        passed = False
        if final:
            primary = final[0]
            meta = primary.get('metadata', {})

            passed = True

            if 'expect_condition' in case:
                passed = passed and primary.get('condition') == case['expect_condition']
                if not passed:
                    safe_print(f"  [FAIL] condition: expected={case['expect_condition']}, actual={primary.get('condition')}")

            if passed and 'expect_channel' in case:
                passed = passed and primary.get('channel') == case['expect_channel']
                if not passed:
                    safe_print(f"  [FAIL] channel: expected={case['expect_channel']}, actual={primary.get('channel')}")

            if passed and 'expect_needs_confirm' in case:
                passed = passed and meta.get('needs_confirmation') == case['expect_needs_confirm']
                if not passed:
                    safe_print(f"  [FAIL] needs_confirm: expected={case['expect_needs_confirm']}, actual={meta.get('needs_confirmation')}")

            if passed and 'expect_domain' in case:
                passed = passed and _normalize_domain(primary.get('domain_name', '')) == case['expect_domain']
                if not passed:
                    safe_print(f"  [FAIL] domain: expected={case['expect_domain']}, actual={primary.get('domain_name')}")

            status = "PASS" if passed else "FAIL"
            safe_print(f"  {status} (turns: {session.turn_count})")
            safe_print(f"    intent={primary.get('intent')}, domain={primary.get('domain_name')}, condition={primary.get('condition')}")
            safe_print(f"    channel={primary.get('channel')}, needs_confirm={meta.get('needs_confirmation')}")
        else:
            safe_print(f"  FAIL (no result)")

        if passed:
            pass_count_local = 1
        else:
            pass_count_local = 0

        existing.append({
            "initial_input": case['initial_input'],
            "follow_ups": case.get('follow_ups', []),
            "output": final,
            "turns": session.turn_count if session else 0,
            "passed": passed,
            "global_index": i
        })

    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(existing, f, ensure_ascii=False, indent=2)
    safe_print(f"\nBatch saved. Total: {len(existing)}")

    if end < len(MULTI_TURN_TEST_CASES):
        safe_print(f"Run again to continue. Next: case {end+1}")


def summarize(results):
    p = sum(1 for r in results if r.get('passed'))
    f = len(results) - p
    safe_print(f"\n========================================")
    safe_print(f"New multi-turn tests: {p} PASS / {f} FAIL / {len(results)} total")
    if results:
        safe_print(f"Pass rate: {p / len(results) * 100:.1f}%")
    if f > 0:
        safe_print(f"\nFailed cases:")
        for r in results:
            if not r.get('passed'):
                safe_print(f"  [{r['global_index']+1}] {r['initial_input']}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "summary":
        results = load_existing()
        summarize(results)
    else:
        batch = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 1
        run_multi_batch(batch)
