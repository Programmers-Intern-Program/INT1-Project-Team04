import json
import sys
from test_cases import TEST_CASES
from runner import parse_task, safe_print, _normalize_domain

BATCH_SIZE = 5
START_IDX = 36
RESULTS_FILE = "new_results.json"


def load_existing():
    try:
        with open(RESULTS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def run_batch(batch_idx):
    existing = load_existing()
    done_count = len(existing)
    start = START_IDX + done_count
    end = min(start + BATCH_SIZE, len(TEST_CASES))

    if start >= len(TEST_CASES):
        safe_print(f"All {done_count} new tests completed.")
        summarize(existing)
        return

    safe_print(f"\n=== Batch {batch_idx}: cases [{start+1}..{end}] ({end-start} cases) ===")

    for i in range(start, end):
        case = TEST_CASES[i]
        safe_print(f"\n[{i+1}/{len(TEST_CASES)}] input: {case['input']}")

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
                    safe_print(f"  [FAIL] intent: expected={expected}, actual={actual}")

            if passed and 'expect_domain' in case:
                expected = case['expect_domain']
                actual = _normalize_domain(primary.get('domain_name', ''))
                passed = actual == expected
                if not passed:
                    safe_print(f"  [FAIL] domain: expected={expected}, actual={primary.get('domain_name')}")

            if passed and 'expect_channel' in case:
                passed = primary.get('channel') == case['expect_channel']
                if not passed:
                    safe_print(f"  [FAIL] channel: expected={case['expect_channel']}, actual={primary.get('channel')}")

            if passed and 'expect_cron' in case:
                passed = primary.get('cron_expr') == case['expect_cron']
                if not passed:
                    safe_print(f"  [FAIL] cron: expected={case['expect_cron']}, actual={primary.get('cron_expr')}")

            if passed and case.get('expect_needs_confirm'):
                passed = metadata.get('needs_confirmation') == True
                if not passed:
                    safe_print(f"  [FAIL] needs_confirm: expected=True, actual={metadata.get('needs_confirmation')}")

        except Exception as e:
            safe_print(f"  parse error: {e}")
            passed = False

        status = "PASS" if passed else "FAIL"
        if passed:
            safe_print(f"  {status}")
        else:
            safe_print(f"  {status}")

        if results_list:
            primary = results_list[0]
            metadata = primary.get('metadata', {})
            safe_print(f"    intent={primary.get('intent')}, domain={primary.get('domain_name')}, query={primary.get('query')}")
            safe_print(f"    condition={primary.get('condition')}, cron={primary.get('cron_expr')}, channel={primary.get('channel')}, api={primary.get('api_type')}")
            safe_print(f"    confidence={metadata.get('confidence')}, needs_confirm={metadata.get('needs_confirmation')}")
            if metadata.get('confirmation_question'):
                safe_print(f"    question={metadata.get('confirmation_question')}")
            if len(results_list) > 1:
                safe_print(f"    ({len(results_list)} tasks)")

        existing.append({
            "input": case['input'],
            "output": results_list,
            "passed": passed,
            "global_index": i
        })

    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(existing, f, ensure_ascii=False, indent=2)
    safe_print(f"\nBatch saved. Total: {len(existing)}")

    if end < len(TEST_CASES):
        safe_print(f"Run again to continue. Next: case {end+1}")


def summarize(results):
    p = sum(1 for r in results if r.get('passed'))
    f = len(results) - p
    safe_print(f"\n========================================")
    safe_print(f"New tests total: {p} PASS / {f} FAIL / {len(results)} total")
    if results:
        safe_print(f"Pass rate: {p / len(results) * 100:.1f}%")
    if f > 0:
        safe_print(f"\nFailed cases:")
        for r in results:
            if not r.get('passed'):
                safe_print(f"  [{r['global_index']+1}] {r['input']}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "summary":
        results = load_existing()
        summarize(results)
    else:
        batch = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 1
        run_batch(batch)
