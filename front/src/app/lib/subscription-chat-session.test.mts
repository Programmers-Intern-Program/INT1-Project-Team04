import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  SUBSCRIPTION_CHAT_SESSION_TTL_MS,
  decodeSubscriptionChatSession,
  encodeSubscriptionChatSession,
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
});
