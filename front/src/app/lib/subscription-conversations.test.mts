import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  deleteSubscriptionSummary,
  getSubscriptionSummaries,
  sendConversationAction,
  sendConversationMessage,
  type SubscriptionConversationFetch,
} from "./subscription-conversations.ts";

describe("subscription conversation API client", () => {
  it("sends a natural-language chat message without a userId", async () => {
    const fetcher: SubscriptionConversationFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/subscription-conversations/messages");
      assert.equal(init?.method, "POST");
      assert.equal(init?.credentials, "include");
      assert.equal(
        init?.body,
        JSON.stringify({
          conversationId: undefined,
          message: "강남구 아파트 매매 실거래가 알려줘",
        }),
      );

      return new Response(
        JSON.stringify({
          conversationId: "conversation-1",
          status: "NEEDS_INPUT",
          assistantMessage: "얼마나 자주 확인할까요?",
          actions: [{ type: "SELECT_CADENCE", label: "매일 오전 9시", value: "DAILY_9AM" }],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await sendConversationMessage(
      { message: "강남구 아파트 매매 실거래가 알려줘" },
      { baseUrl: "http://api.test", fetcher },
    );

    assert.equal(result.ok, true);
    assert.equal(result.ok ? result.data.status : "", "NEEDS_INPUT");
  });

  it("sends a structured conversation action", async () => {
    const fetcher: SubscriptionConversationFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/subscription-conversations/messages");
      assert.equal(init?.method, "POST");
      assert.equal(init?.credentials, "include");
      assert.equal(
        init?.body,
        JSON.stringify({
          conversationId: "conversation-1",
          action: { type: "SELECT_CHANNEL", value: "TELEGRAM_DM" },
        }),
      );

      return new Response(
        JSON.stringify({
          conversationId: "conversation-1",
          status: "READY_FOR_CONFIRMATION",
          assistantMessage: "아래 내용으로 알림을 시작할까요?",
          actions: [],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await sendConversationAction(
      {
        conversationId: "conversation-1",
        action: { type: "SELECT_CHANNEL", value: "TELEGRAM_DM" },
      },
      { baseUrl: "http://api.test/", fetcher },
    );

    assert.equal(result.ok, true);
    assert.equal(result.ok ? result.data.status : "", "READY_FOR_CONFIRMATION");
  });

  it("loads active subscription summaries", async () => {
    const fetcher: SubscriptionConversationFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/subscriptions");
      assert.equal(init?.method, "GET");
      assert.equal(init?.credentials, "include");

      return new Response(
        JSON.stringify([
          {
            id: "sub-1",
            query: "강남구 아파트 실거래가",
            domainLabel: "부동산",
            cadenceLabel: "매일 오전 9시",
            notificationChannel: "TELEGRAM_DM",
            channelLabel: "Telegram",
            nextRun: "2026-04-28T09:00:00",
            active: true,
          },
        ]),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await getSubscriptionSummaries({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: true,
      data: [
        {
          id: "sub-1",
          query: "강남구 아파트 실거래가",
          domainLabel: "부동산",
          cadenceLabel: "매일 오전 9시",
          notificationChannel: "TELEGRAM_DM",
          channelLabel: "Telegram",
          nextRun: "2026-04-28T09:00:00",
          active: true,
        },
      ],
    });
  });

  it("deletes a subscription summary through the backend API", async () => {
    const fetcher: SubscriptionConversationFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/subscriptions/sub-1");
      assert.equal(init?.method, "DELETE");
      assert.equal(init?.credentials, "include");

      return new Response(null, { status: 204 });
    };

    const result = await deleteSubscriptionSummary("sub-1", {
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, { ok: true });
  });

  it("returns backend error details", async () => {
    const fetcher: SubscriptionConversationFetch = async () =>
      new Response(
        JSON.stringify({
          code: "UNAUTHENTICATED",
          message: "로그인이 필요합니다.",
        }),
        { status: 401, headers: { "Content-Type": "application/json" } },
      );

    const result = await getSubscriptionSummaries({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, {
      ok: false,
      error: {
        code: "UNAUTHENTICATED",
        message: "로그인이 필요합니다.",
      },
    });
  });
});
