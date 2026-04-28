import type {
  ConversationActionOption,
  ConversationResponse,
} from "./subscription-conversations";

export type ChatMessage = {
  id: string;
  role: "assistant" | "user";
  content: string;
  status?: "pending" | "error";
};

export type DebugJsonSnapshot = {
  request: string;
  response: string;
};

export type SubscriptionChatSessionSnapshot = {
  messages: ChatMessage[];
  conversationId: string | null;
  actions: ConversationActionOption[];
  draft: ConversationResponse["draft"] | null;
  debugJson: DebugJsonSnapshot;
};

type StoredSubscriptionChatSession = {
  version: 1;
  savedAt: number;
  snapshot: SubscriptionChatSessionSnapshot;
};

export const SUBSCRIPTION_CHAT_SESSION_KEY = "subscription-chat-session";
export const SUBSCRIPTION_CHAT_SESSION_TTL_MS = 30 * 60 * 1000;

export function encodeSubscriptionChatSession(
  snapshot: SubscriptionChatSessionSnapshot,
  now = Date.now(),
): string {
  return JSON.stringify({
    version: 1,
    savedAt: now,
    snapshot,
  } satisfies StoredSubscriptionChatSession);
}

export function decodeSubscriptionChatSession(
  raw: string,
  now = Date.now(),
): SubscriptionChatSessionSnapshot | null {
  try {
    const stored = JSON.parse(raw) as Partial<StoredSubscriptionChatSession>;
    if (
      stored.version !== 1 ||
      typeof stored.savedAt !== "number" ||
      now - stored.savedAt > SUBSCRIPTION_CHAT_SESSION_TTL_MS ||
      !isSnapshot(stored.snapshot)
    ) {
      return null;
    }

    return stored.snapshot;
  } catch {
    return null;
  }
}

function isSnapshot(value: unknown): value is SubscriptionChatSessionSnapshot {
  if (!value || typeof value !== "object") {
    return false;
  }

  const snapshot = value as Partial<SubscriptionChatSessionSnapshot>;
  return (
    Array.isArray(snapshot.messages) &&
    snapshot.messages.every(isChatMessage) &&
    (typeof snapshot.conversationId === "string" || snapshot.conversationId === null) &&
    Array.isArray(snapshot.actions) &&
    (typeof snapshot.debugJson?.request === "string") &&
    (typeof snapshot.debugJson?.response === "string")
  );
}

function isChatMessage(value: unknown): value is ChatMessage {
  if (!value || typeof value !== "object") {
    return false;
  }

  const message = value as Partial<ChatMessage>;
  return (
    typeof message.id === "string" &&
    (message.role === "assistant" || message.role === "user") &&
    typeof message.content === "string" &&
    (message.status === undefined || message.status === "pending" || message.status === "error")
  );
}
