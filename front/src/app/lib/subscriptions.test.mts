import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import {
  buildSubscriptionPayload,
  buildDomainPresets,
  CADENCE_PRESETS,
  CHANNEL_PRESETS,
  createSubscription,
  connectDiscordNotification,
  disconnectNotificationEndpoint,
  getDomains,
  getApiBaseUrl,
  getNotificationEndpoints,
  startTelegramNotificationConnect,
  validateSubscriptionForm,
  shouldShowEmailAddressInput,
  type CreateSubscriptionRequest,
  type SubscriptionFetch,
} from "./subscriptions.ts";

describe("subscription form helpers", () => {
  it("does not expose AI-flavored explanatory copy in the product UI", () => {
    const sourceFiles = [
      new URL("../components/subscription-chat.tsx", import.meta.url),
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
      "Discord 사용자 ID",
      "Telegram chat_id",
    ];

    assert.deepEqual(
      bannedCopy.filter((copy) => source.includes(copy)),
      [],
    );
  });

  it("does not expose user or domain ID inputs in the product UI", () => {
    const source = readFileSync(
      new URL("../components/subscription-chat.tsx", import.meta.url),
      "utf8",
    );
    const bannedCopy = [
      "사용자 ID",
      "도메인 ID",
      'htmlFor="userId"',
      'htmlFor="domainId"',
      'id="userId"',
      'id="domainId"',
      "<pre",
    ];

    assert.deepEqual(
      bannedCopy.filter((copy) => source.includes(copy)),
      [],
    );
  });

  it("does not expose backend subscription internals in the product UI", () => {
    const source = readFileSync(
      new URL("../components/subscription-chat.tsx", import.meta.url),
      "utf8",
    );
    const bannedCopy = [
      "등록 결과",
      "등록 내용",
      "반복 설정",
      'label="subscription"',
      'label="schedule"',
      'label="active"',
      'label="nextRun"',
      "font-mono",
      "cronExpr ??",
    ];

    assert.deepEqual(
      bannedCopy.filter((copy) => source.includes(copy)),
      [],
    );
  });

  it("uses the chat subscription experience on the authenticated home screen", () => {
    const pageSource = readFileSync(new URL("../page.tsx", import.meta.url), "utf8");

    assert.equal(pageSource.includes("SubscriptionChat"), true);
    assert.equal(pageSource.includes("SubscriptionMvp"), false);
  });

  it("does not render the old domain cadence channel fieldsets in the chat UI", () => {
    const source = readFileSync(
      new URL("../components/subscription-chat.tsx", import.meta.url),
      "utf8",
    );
    const bannedSource = [
      "selectedDomainId",
      "CADENCE_PRESETS",
      "CHANNEL_PRESETS",
      "<fieldset",
      "buildSubscriptionPayload",
      "createSubscription(",
    ];

    assert.deepEqual(
      bannedSource.filter((copy) => source.includes(copy)),
      [],
    );
  });

  it("renders a pending assistant response while a chat request is in flight", () => {
    const source = readFileSync(
      new URL("../components/subscription-chat.tsx", import.meta.url),
      "utf8",
    );

    assert.equal(source.includes('status?: "pending" | "error"'), true);
    assert.equal(source.includes("답변을 준비하고 있어요"), true);
    assert.equal(source.includes('aria-live="polite"'), true);
  });

  it("keeps the chat scrolled to the latest conversation update", () => {
    const source = readFileSync(
      new URL("../components/subscription-chat.tsx", import.meta.url),
      "utf8",
    );

    assert.equal(source.includes("useRef"), true);
    assert.equal(source.includes("chatEndRef"), true);
    assert.equal(source.includes("scrollIntoView"), true);
    assert.equal(source.includes("[messages, actions, draft]"), true);
  });

  it("renders delete controls for active subscription summaries", () => {
    const source = readFileSync(
      new URL("../components/subscription-chat.tsx", import.meta.url),
      "utf8",
    );

    assert.equal(source.includes("deleteSubscriptionSummary"), true);
    assert.equal(source.includes("handleDeleteSubscription"), true);
    assert.equal(source.includes("삭제"), true);
  });

  it("maps backend domain names to product labels and examples", () => {
    const domains = buildDomainPresets([
      { id: 11, name: "real-estate" },
      { id: 12, name: "law-regulation" },
      { id: 13, name: "recruitment" },
      { id: 14, name: "auction" },
    ]);

    assert.deepEqual(
      domains.map((domain) => [domain.id, domain.name, domain.label]),
      [
        [11, "real-estate", "부동산"],
        [12, "law-regulation", "법률/규제"],
        [13, "recruitment", "채용"],
        [14, "auction", "경매/희소매물"],
      ],
    );
  });

  it("falls back to the backend name when there is no product copy for a domain", () => {
    const [domain] = buildDomainPresets([{ id: 99, name: "custom-domain" }]);

    assert.deepEqual(domain, {
      id: 99,
      name: "custom-domain",
      label: "custom-domain",
      example: "custom-domain 변경사항이 생기면 알려줘",
    });
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
      notificationTargetAddress: "",
    });

    assert.deepEqual(payload, {
      domainId: 3,
      query: "강남 투룸 전세 시세 바뀌면 알려줘",
      cronExpr: "0 0 * * * *",
      notificationChannel: "TELEGRAM_DM",
    });
  });

  it("keeps direct email target in the backend subscription payload", () => {
    const payload = buildSubscriptionPayload({
      query: "  강남 투룸 전세 시세 바뀌면 알려줘  ",
      selectedDomainId: 3,
      cadenceId: "hourly",
      notificationChannel: "EMAIL",
      notificationTargetAddress: "  user@example.com  ",
    });

    assert.deepEqual(payload, {
      domainId: 3,
      query: "강남 투룸 전세 시세 바뀌면 알려줘",
      cronExpr: "0 0 * * * *",
      notificationChannel: "EMAIL",
      notificationTargetAddress: "user@example.com",
    });
  });

  it("returns field errors for an empty domain, query, and email target", () => {
    const errors = validateSubscriptionForm({
      query: " ",
      selectedDomainId: 0,
      cadenceId: "hourly",
      notificationChannel: "EMAIL",
      notificationTargetAddress: " ",
    });

    assert.deepEqual(errors, {
      domainId: "감시 영역을 선택해 주세요.",
      query: "감시할 요청을 입력해 주세요.",
      notificationTargetAddress: "알림 받을 이메일을 입력해 주세요.",
    });
  });

  it("does not require email input when an email endpoint is already connected", () => {
    const errors = validateSubscriptionForm(
      {
        query: "강남 투룸 전세 시세 바뀌면 알려줘",
        selectedDomainId: 3,
        cadenceId: "hourly",
        notificationChannel: "EMAIL",
        notificationTargetAddress: " ",
      },
      { selectedEndpointConnected: true },
    );

    assert.deepEqual(errors, {});
  });

  it("hides the email input when an email endpoint is already connected", () => {
    assert.equal(shouldShowEmailAddressInput("EMAIL", false), true);
    assert.equal(shouldShowEmailAddressInput("EMAIL", true), false);
    assert.equal(shouldShowEmailAddressInput("DISCORD_DM", true), false);
    assert.equal(shouldShowEmailAddressInput("TELEGRAM_DM", true), false);
  });

  it("does not require raw Discord or Telegram IDs in form validation", () => {
    const errors = validateSubscriptionForm({
      query: "강남 투룸 전세 시세 바뀌면 알려줘",
      selectedDomainId: 3,
      cadenceId: "hourly",
      notificationChannel: "DISCORD_DM",
      notificationTargetAddress: " ",
    });

    assert.deepEqual(errors, {});
  });
});

describe("subscription API client", () => {
  const payload: CreateSubscriptionRequest = {
    domainId: 3,
    query: "넥슨 Java 3년 이상 채용 뜨면 알려줘",
    cronExpr: "0 0 * * * *",
    notificationChannel: "DISCORD_DM",
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

  it("returns domain presets for a successful backend response", async () => {
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
        {
          id: 1,
          name: "real-estate",
          label: "부동산",
          example: "강남 투룸 전세 시세 바뀌면 알려줘",
        },
        {
          id: 2,
          name: "law-regulation",
          label: "법률/규제",
          example: "개인정보 보호법 개정안 나오면 알려줘",
        },
      ],
    });
  });

  it("loads notification endpoint connection statuses", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/notification-endpoints");
      assert.equal(init?.method, "GET");
      assert.equal(init?.credentials, "include");

      return new Response(
        JSON.stringify([
          { channel: "DISCORD_DM", connected: true, targetLabel: "연결됨" },
          { channel: "TELEGRAM_DM", connected: false, targetLabel: null },
          { channel: "EMAIL", connected: false, targetLabel: null },
        ]),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await getNotificationEndpoints({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: true,
      data: [
        { channel: "DISCORD_DM", connected: true, targetLabel: "연결됨" },
        { channel: "TELEGRAM_DM", connected: false, targetLabel: null },
        { channel: "EMAIL", connected: false, targetLabel: null },
      ],
    });
  });

  it("requests Discord notification connection", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/notification-endpoints/discord/connect");
      assert.equal(init?.method, "POST");
      assert.equal(init?.credentials, "include");

      return new Response(
        JSON.stringify({
          channel: "DISCORD_DM",
          connected: false,
          authorizationUrl: "http://api.test/api/auth/oauth/discord/authorize",
          message: "Discord 로그인이 필요합니다.",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await connectDiscordNotification({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: true,
      data: {
        channel: "DISCORD_DM",
        connected: false,
        targetLabel: null,
        connectUrl: null,
        authorizationUrl: "http://api.test/api/auth/oauth/discord/authorize",
        message: "Discord 로그인이 필요합니다.",
      },
    });
  });

  it("requests Telegram notification deep link", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/notification-endpoints/telegram/connect");
      assert.equal(init?.method, "POST");
      assert.equal(init?.credentials, "include");

      return new Response(
        JSON.stringify({
          channel: "TELEGRAM_DM",
          connected: false,
          connectUrl: "https://t.me/int1_test_bot?start=token-1",
          message: "Telegram 봇을 열어 연결을 완료해 주세요.",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await startTelegramNotificationConnect({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: true,
      data: {
        channel: "TELEGRAM_DM",
        connected: false,
        targetLabel: null,
        connectUrl: "https://t.me/int1_test_bot?start=token-1",
        authorizationUrl: null,
        message: "Telegram 봇을 열어 연결을 완료해 주세요.",
      },
    });
  });

  it("requests notification endpoint disconnection", async () => {
    const fetcher: SubscriptionFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/notification-endpoints/TELEGRAM_DM");
      assert.equal(init?.method, "DELETE");
      assert.equal(init?.credentials, "include");

      return new Response(
        JSON.stringify({
          channel: "TELEGRAM_DM",
          connected: false,
          message: "Telegram 연결이 해제되었습니다.",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await disconnectNotificationEndpoint("TELEGRAM_DM", {
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: true,
      data: {
        channel: "TELEGRAM_DM",
        connected: false,
        targetLabel: null,
        connectUrl: null,
        authorizationUrl: null,
        message: "Telegram 연결이 해제되었습니다.",
      },
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
