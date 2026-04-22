import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  buildSubscriptionPayload,
  CADENCE_PRESETS,
  createSubscription,
  DOMAIN_PRESETS,
  getApiBaseUrl,
  validateSubscriptionForm,
  type CreateSubscriptionRequest,
  type SubscriptionFetch,
} from "./subscriptions.ts";

describe("subscription form helpers", () => {
  it("defines the four MVP domain presets with stable backend IDs", () => {
    assert.deepEqual(
      DOMAIN_PRESETS.map((domain) => [domain.id, domain.label]),
      [
        [1, "부동산"],
        [2, "법률/규제"],
        [3, "채용"],
        [4, "경매/희소매물"],
      ],
    );
  });

  it("maps cadence presets to Spring cron expressions", () => {
    assert.deepEqual(
      CADENCE_PRESETS.map((cadence) => [cadence.id, cadence.cronExpr]),
      [
        ["hourly", "0 0 * * * *"],
        ["dailyMorning", "0 0 9 * * *"],
        ["weekdayMorning", "0 0 9 * * MON-FRI"],
      ],
    );
  });

  it("builds the backend subscription payload from form state", () => {
    const payload = buildSubscriptionPayload({
      query: "  강남 투룸 전세 시세 바뀌면 알려줘  ",
      userId: "1",
      domainId: "1",
      cadenceId: "hourly",
    });

    assert.deepEqual(payload, {
      userId: 1,
      domainId: 1,
      query: "강남 투룸 전세 시세 바뀌면 알려줘",
      cronExpr: "0 0 * * * *",
    });
  });

  it("returns field errors for empty query and invalid IDs", () => {
    const errors = validateSubscriptionForm({
      query: " ",
      userId: "0",
      domainId: "abc",
      cadenceId: "hourly",
    });

    assert.deepEqual(errors, {
      query: "감시할 요청을 입력해 주세요.",
      userId: "사용자 ID는 1 이상의 숫자여야 합니다.",
      domainId: "도메인 ID는 1 이상의 숫자여야 합니다.",
    });
  });
});

describe("subscription API client", () => {
  const payload: CreateSubscriptionRequest = {
    userId: 1,
    domainId: 3,
    query: "넥슨 Java 3년 이상 채용 뜨면 알려줘",
    cronExpr: "0 0 * * * *",
  };

  it("uses localhost backend when no public API base URL is set", () => {
    assert.equal(getApiBaseUrl(), "http://localhost:8080");
  });

  it("returns subscription data for a successful backend response", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      const headers = init?.headers as Record<string, string>;

      assert.equal(input, "http://api.test/api/subscriptions");
      assert.equal(init?.method, "POST");
      assert.equal(headers["Content-Type"], "application/json");
      assert.equal(init?.body, JSON.stringify(payload));

      return new Response(
        JSON.stringify({
          id: "sub-1",
          userId: 1,
          domainId: 3,
          query: payload.query,
          active: true,
          createdAt: "2026-04-22T09:00:00",
          scheduleId: "sch-1",
          cronExpr: payload.cronExpr,
          nextRun: "2026-04-22T10:00:00",
        }),
        { status: 201, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await createSubscription(payload, {
      baseUrl: "http://api.test/",
      fetcher,
    });

    assert.equal(result.ok, true);
    assert.equal(result.ok ? result.data.scheduleId : "", "sch-1");
  });

  it("returns backend error details for non-2xx responses", async () => {
    const fetcher: SubscriptionFetch = async () =>
      new Response(
        JSON.stringify({
          success: false,
          code: "USER_NOT_FOUND",
          message: "사용자를 찾을 수 없습니다.",
          timestamp: "2026-04-22T09:00:00",
        }),
        { status: 404, headers: { "Content-Type": "application/json" } },
      );

    const result = await createSubscription(payload, {
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: false,
      error: {
        code: "USER_NOT_FOUND",
        message: "사용자를 찾을 수 없습니다.",
      },
    });
  });

  it("returns a network error when the request cannot reach the backend", async () => {
    const fetcher: SubscriptionFetch = async () => {
      throw new Error("connection refused");
    };

    const result = await createSubscription(payload, {
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: false,
      error: {
        code: "NETWORK_ERROR",
        message: "백엔드 서버에 연결할 수 없습니다.",
      },
    });
  });
});
