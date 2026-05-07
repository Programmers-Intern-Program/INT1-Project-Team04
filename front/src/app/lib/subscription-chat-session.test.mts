import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  STALE_CONVERSATION_MESSAGE,
  SUBSCRIPTION_CHAT_SESSION_TTL_MS,
  decodeSubscriptionChatSession,
  encodeSubscriptionChatSession,
  isStaleConversationError,
  parsePendingChannelSelection,
  pendingChannelSelectionForAction,
  shouldClearPendingChannelForResponse,
  type SubscriptionChatSessionSnapshot,
} from "./subscription-chat-session.ts";

describe("subscription chat session persistence", () => {
  it("round-trips the chat state needed after an OAuth return", () => {
    const snapshot: SubscriptionChatSessionSnapshot = {
      messages: [
        { id: "assistant-initial", role: "assistant", content: "어떤 알림을 만들까요?" },
        { id: "user-1", role: "user", content: "강남구 실거래가 알려줘" },
      ],
      conversationId: "conversation-1",
      actions: [
        {
          type: "SELECT_CHANNEL",
          label: "Discord",
          value: "DISCORD_DM",
          connected: false,
          requiresConnection: true,
        },
      ],
      draft: null,
      debugJson: {
        request: "{}",
        response: "{}",
      },
    };

    const encoded = encodeSubscriptionChatSession(snapshot, 1_000);

    assert.deepEqual(decodeSubscriptionChatSession(encoded, 1_000), snapshot);
  });

  it("drops expired or malformed chat snapshots", () => {
    const snapshot: SubscriptionChatSessionSnapshot = {
      messages: [{ id: "assistant-initial", role: "assistant", content: "어떤 알림을 만들까요?" }],
      conversationId: null,
      actions: [],
      draft: null,
      debugJson: {
        request: "{}",
        response: "{}",
      },
    };
    const encoded = encodeSubscriptionChatSession(snapshot, 1_000);

    assert.equal(
      decodeSubscriptionChatSession(encoded, 1_000 + SUBSCRIPTION_CHAT_SESSION_TTL_MS + 1),
      null,
    );
    assert.equal(decodeSubscriptionChatSession("{", 1_000), null);
  });

  it("detects stale conversation errors only when an old conversation id was sent", () => {
    assert.equal(
      isStaleConversationError({ code: "SESSION_NOT_FOUND", message: "세션을 찾을 수 없습니다." }, "conversation-1"),
      true,
    );
    assert.equal(
      isStaleConversationError({ code: "INVALID_REQUEST", message: "요청 값이 올바르지 않습니다." }, "conversation-1"),
      true,
    );
    assert.equal(
      isStaleConversationError({ code: "INVALID_REQUEST", message: "요청 값이 올바르지 않습니다." }, null),
      false,
    );
    assert.equal(
      isStaleConversationError({ code: "INSUFFICIENT_TOKEN", message: "토큰이 부족합니다." }, "conversation-1"),
      false,
    );
    assert.equal(STALE_CONVERSATION_MESSAGE, "이전 대화가 만료되어 새로 시작할게요.");
  });

  it("marks an unconnected Email channel action as pending so endpoint save can resume it", () => {
    assert.deepEqual(
      pendingChannelSelectionForAction("conversation-1", {
        type: "SELECT_CHANNEL",
        label: "Email",
        value: "EMAIL",
        connected: false,
        requiresConnection: false,
      }),
      { conversationId: "conversation-1", channel: "EMAIL" },
    );

    assert.equal(
      pendingChannelSelectionForAction("conversation-1", {
        type: "SELECT_CHANNEL",
        label: "Email",
        value: "EMAIL",
        connected: true,
        requiresConnection: false,
      }),
      null,
    );
  });

  it("parses Email as a pending channel selection", () => {
    assert.deepEqual(
      parsePendingChannelSelection(JSON.stringify({ conversationId: "conversation-1", channel: "EMAIL" })),
      { conversationId: "conversation-1", channel: "EMAIL" },
    );
    assert.equal(
      parsePendingChannelSelection(JSON.stringify({ conversationId: "conversation-1", channel: "SMS" })),
      null,
    );
  });

  it("clears pending channel when a new needs-input conversation replaces it", () => {
    assert.equal(
      shouldClearPendingChannelForResponse(
        { conversationId: "conversation-1", channel: "EMAIL" },
        { conversationId: "conversation-2", status: "NEEDS_INPUT" },
      ),
      true,
    );
    assert.equal(
      shouldClearPendingChannelForResponse(
        { conversationId: "conversation-1", channel: "EMAIL" },
        { conversationId: "conversation-1", status: "NEEDS_INPUT" },
      ),
      false,
    );
  });
});
