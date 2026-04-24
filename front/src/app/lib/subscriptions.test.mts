import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import {
  buildSubscriptionPayload,
  CADENCE_PRESETS,
  CHANNEL_PRESETS,
  createSubscription,
  DOMAIN_PRESETS,
  getApiBaseUrl,
  getDomains,
  validateSubscriptionForm,
  type CreateSubscriptionRequest,
  type SubscriptionFetch,
} from "./subscriptions.ts";

describe("subscription form helpers", () => {
  it("does not expose AI-flavored explanatory copy in the product UI", () => {
    const sourceFiles = [
      new URL("../components/subscription-mvp.tsx", import.meta.url),
      new URL("../layout.tsx", import.meta.url),
      new URL("./subscriptions.ts", import.meta.url),
    ];
    const source = sourceFiles
      .map((file) => readFileSync(file, "utf8"))
      .join("\n");
    const bannedCopy = [
      "AI monitoring agent",
      "AI 데이터",
      "AI 에이전트",
      "에이전트",
      "자연어",
      "MVP",
      "백엔드",
      "등록 미리보기",
      ">cron<",
      "브리핑",
    ];

    assert.deepEqual(
      bannedCopy.filter((copy) => source.includes(copy)),
      [],
    );
  });

  it("does not expose user or domain ID inputs in the product UI", () => {
    const source = readFileSync(
      new URL("../components/subscription-mvp.tsx", import.meta.url),
      "utf8",
    );
    const bannedCopy = [
      "사용자 ID",
      "도메인 ID",
      'htmlFor="userId"',
      'htmlFor="domainId"',
      'id="userId"',
      'id="domainId"',
      "JSON.stringify",
      "<pre",
    ];

    assert.deepEqual(
      bannedCopy.filter((copy) => source.includes(copy)),
      [],
    );
  });

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

  it("maps notification channels to backend enum values", () => {
    assert.deepEqual(
      CHANNEL_PRESETS.map((channel) => [channel.id, channel.label]),
      [
        ["DISCORD_DM", "Discord"],
        ["TELEGRAM_DM", "Telegram"],
        ["EMAIL", "Email"],
      ],
    );
  });

  it("builds the backend subscription payload from form state", () => {
    const payload = buildSubscriptionPayload({
      query: "  강남 투룸 전세 시세 바뀌면 알려줘  ",
      selectedDomainId: 3,
      cadenceId: "hourly",
      notificationChannel: "TELEGRAM_DM",
      notificationTargetAddress: "  123456789  ",
    });

    assert.deepEqual(payload, {
      domainId: 3,
      query: "강남 투룸 전세 시세 바뀌면 알려줘",
      cronExpr: "0 0 * * * *",
      notificationChannel: "TELEGRAM_DM",
      notificationTargetAddress: "123456789",
    });
  });

  it("returns field errors for an empty query and empty notification target", () => {
    const errors = validateSubscriptionForm({
      query: " ",
      selectedDomainId: 1,
      cadenceId: "hourly",
      notificationChannel: "EMAIL",
      notificationTargetAddress: " ",
    });

    assert.deepEqual(errors, {
      query: "감시할 요청을 입력해 주세요.",
      notificationTargetAddress: "알림을 받을 대상을 입력해 주세요.",
    });
  });
});

describe("subscription API client", () => {
  const payload: CreateSubscriptionRequest = {
    domainId: 3,
    query: "넥슨 Java 3년 이상 채용 뜨면 알려줘",
    cronExpr: "0 0 * * * *",
    notificationChannel: "DISCORD_DM",
    notificationTargetAddress: "987654321",
  };

  it("uses localhost backend when no public API base URL is set", () => {
    assert.equal(getApiBaseUrl(), "http://localhost:8080");
  });

  it("returns subscription data for a successful backend response", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      const headers = init?.headers as Record<string, string>;

      assert.equal(input, "http://api.test/api/subscriptions");
      assert.equal(init?.method, "POST");
      assert.equal(init?.credentials, "include");
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

  it("returns domain summaries for a successful backend response", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/domains");
      assert.equal(init?.method, "GET");

      return new Response(
        JSON.stringify([
          { id: 1, name: "real-estate" },
          { id: 2, name: "law-regulation" },
        ]),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await getDomains({
      baseUrl: "http://api.test/",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: true,
      data: [
        { id: 1, name: "real-estate" },
        { id: 2, name: "law-regulation" },
      ],
    });
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

  it("returns backend error details when domain loading fails", async () => {
    const fetcher: SubscriptionFetch = async () =>
      new Response(
        JSON.stringify({
          code: "REQUEST_FAILED",
          message: "도메인 목록을 불러오지 못했습니다.",
        }),
        { status: 500, headers: { "Content-Type": "application/json" } },
      );

    const result = await getDomains({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: false,
      error: {
        code: "REQUEST_FAILED",
        message: "도메인 목록을 불러오지 못했습니다.",
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
        message: "서버에 연결할 수 없습니다.",
      },
    });
  });

  it("returns a domain loading network error when the request cannot reach the backend", async () => {
    const fetcher: SubscriptionFetch = async () => {
      throw new Error("connection refused");
    };

    const result = await getDomains({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: false,
      error: {
        code: "NETWORK_ERROR",
        message: "감시 영역을 불러올 수 없습니다.",
      },
    });
  });
});
