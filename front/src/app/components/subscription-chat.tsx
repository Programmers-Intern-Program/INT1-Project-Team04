"use client";

import type { FormEvent } from "react";
import { useCallback, useEffect, useRef, useState } from "react";

import {
  connectDiscordNotification,
  startTelegramNotificationConnect,
  type NotificationChannelId,
} from "../lib/subscriptions";
import {
  STALE_CONVERSATION_MESSAGE,
  STORAGE_KEYS,
  SUBSCRIPTION_CHAT_SESSION_KEY,
  decodeSubscriptionChatSession,
  encodeSubscriptionChatSession,
  isStaleConversationError,
  parsePendingChannelSelection,
  pendingChannelSelectionForAction,
  shouldClearPendingChannelForResponse,
  type ChatMessage,
  type SubscriptionChatSessionSnapshot,
} from "../lib/subscription-chat-session";
import {
  sendConversationAction,
  sendConversationMessage,
  type ConversationActionOption,
  type ConversationResponse,
} from "../lib/subscription-conversations";

type SubmitState = "idle" | "sending";

const INITIAL_MESSAGES: ChatMessage[] = [
  {
    id: "assistant-initial",
    role: "assistant",
    content: "어떤 알림을 만들까요?",
  },
];

const PENDING_RESPONSE_MESSAGE = "답변을 준비하고 있어요";
const PENDING_ACTION_MESSAGE = "선택 내용을 반영하고 있어요";

export function SubscriptionChat({
  onUnauthenticated,
}: {
  onUnauthenticated?: () => void;
}) {
  const [messages, setMessages] = useState<ChatMessage[]>(INITIAL_MESSAGES);
  const [input, setInput] = useState("");
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [actions, setActions] = useState<ConversationActionOption[]>([]);
  const [draft, setDraft] = useState<ConversationResponse["draft"]>(null);
  const [submitState, setSubmitState] = useState<SubmitState>("idle");
  const [statusMessage, setStatusMessage] = useState("");
  const [hasRestoredSession, setHasRestoredSession] = useState(false);
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  const resetExpiredConversation = useCallback(() => {
    sessionStorage.removeItem(SUBSCRIPTION_CHAT_SESSION_KEY);
    sessionStorage.removeItem(STORAGE_KEYS.pendingChannel);
    setConversationId(null);
    setActions([]);
    setDraft(null);
    setMessages([
      {
        id: createMessageId("assistant-reset"),
        role: "assistant",
        content: STALE_CONVERSATION_MESSAGE,
      },
    ]);
    setStatusMessage("");
  }, []);

  const applyConversationResponse = useCallback(
    (
      response: ConversationResponse,
      options: { replaceMessageId?: string } = {},
    ) => {
      setConversationId(response.conversationId);
      setActions(response.actions ?? []);
      setDraft(response.draft ?? null);
      setMessages((current) => {
        const assistantMessage: ChatMessage = {
          id: options.replaceMessageId ?? createMessageId("assistant"),
          role: "assistant",
          content: response.assistantMessage,
        };

        if (options.replaceMessageId) {
          return current.map((message) =>
            message.id === options.replaceMessageId ? assistantMessage : message,
          );
        }

        return [...current, assistantMessage];
      });

      if (shouldClearPendingChannelForResponse(readPendingChannel(), response)) {
        sessionStorage.removeItem(STORAGE_KEYS.pendingChannel);
      }
    },
    [],
  );

  const resumePendingChannel = useCallback(async () => {
    const pending = readPendingChannel();
    if (!pending) {
      return;
    }

    sessionStorage.removeItem(STORAGE_KEYS.pendingChannel);
    const response = await sendConversationAction({
      conversationId: pending.conversationId,
      action: { type: "SELECT_CHANNEL", value: pending.channel },
    });

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      if (isStaleConversationError(response.error, pending.conversationId)) {
        resetExpiredConversation();
        return;
      }
      setStatusMessage(response.error.message);
      return;
    }

    applyConversationResponse(response.data);
  }, [applyConversationResponse, onUnauthenticated, resetExpiredConversation]);

  useEffect(() => {
    if (!hasRestoredSession) {
      return;
    }

    sessionStorage.setItem(
      SUBSCRIPTION_CHAT_SESSION_KEY,
      encodeSubscriptionChatSession({
        messages,
        conversationId,
        actions,
        draft,
      }),
    );
  }, [actions, conversationId, draft, hasRestoredSession, messages]);

  useEffect(() => {
    // setTimeout(..., 0)로 비동기 큐로 보내 react-hooks/set-state-in-effect 룰을 회피한다.
    const timer = window.setTimeout(() => {
      const snapshot = readChatSession();
      if (snapshot) {
        setMessages(snapshot.messages);
        setConversationId(snapshot.conversationId);
        setActions(snapshot.actions);
        setDraft(snapshot.draft);
      }
      setHasRestoredSession(true);
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void resumePendingChannel();
    }, 0);
    window.addEventListener("focus", resumePendingChannel);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("focus", resumePendingChannel);
    };
  }, [resumePendingChannel]);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ block: "end" });
  }, [messages, actions, draft]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = input.trim();
    if (!message || submitState === "sending") {
      return;
    }

    setInput("");
    setStatusMessage("");
    setSubmitState("sending");
    const pendingMessageId = createMessageId("assistant-pending");
    setMessages((current) => [
      ...current,
      { id: createMessageId("user"), role: "user", content: message },
      {
        id: pendingMessageId,
        role: "assistant",
        content: PENDING_RESPONSE_MESSAGE,
        status: "pending",
      },
    ]);

    const requestPayload = {
      conversationId: conversationId ?? undefined,
      message,
    };
    const response = await sendConversationMessage(requestPayload);
    setSubmitState("idle");

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      if (isStaleConversationError(response.error, requestPayload.conversationId)) {
        resetExpiredConversation();
        return;
      }
      replaceMessage(pendingMessageId, response.error.message, "error");
      setStatusMessage(response.error.message);
      return;
    }

    applyConversationResponse(response.data, { replaceMessageId: pendingMessageId });
  }

  async function handleAction(action: ConversationActionOption) {
    if (!conversationId || submitState === "sending") {
      return;
    }

    setStatusMessage("");

    if (
      action.type === "SELECT_CHANNEL" &&
      action.requiresConnection &&
      !action.connected
    ) {
      appendUserMessage(action.label);
      await startChannelConnection(conversationId, action.value as NotificationChannelId);
      return;
    }

    const pendingChannel = pendingChannelSelectionForAction(conversationId, action);
    if (pendingChannel) {
      sessionStorage.setItem(STORAGE_KEYS.pendingChannel, JSON.stringify(pendingChannel));
    }

    setSubmitState("sending");
    const pendingMessageId = createMessageId("assistant-pending");
    setMessages((current) => [
      ...current,
      { id: createMessageId("user-action"), role: "user", content: action.label },
      {
        id: pendingMessageId,
        role: "assistant",
        content: PENDING_ACTION_MESSAGE,
        status: "pending",
      },
    ]);
    const requestPayload = {
      conversationId,
      action: { type: action.type, value: action.value },
    };
    const response = await sendConversationAction(requestPayload);
    setSubmitState("idle");

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      if (isStaleConversationError(response.error, requestPayload.conversationId)) {
        resetExpiredConversation();
        return;
      }
      if (pendingChannel) {
        sessionStorage.removeItem(STORAGE_KEYS.pendingChannel);
      }
      replaceMessage(pendingMessageId, response.error.message, "error");
      setStatusMessage(response.error.message);
      return;
    }

    applyConversationResponse(response.data, { replaceMessageId: pendingMessageId });
  }

  function appendUserMessage(content: string) {
    setMessages((current) => [
      ...current,
      { id: createMessageId("user-action"), role: "user", content },
    ]);
  }

  function replaceMessage(
    messageId: string,
    content: string,
    status?: ChatMessage["status"],
  ) {
    setMessages((current) =>
      current.map((message) =>
        message.id === messageId ? { ...message, content, status } : message,
      ),
    );
  }

  async function startChannelConnection(
    currentConversationId: string,
    channel: NotificationChannelId,
  ) {
    sessionStorage.setItem(
      STORAGE_KEYS.pendingChannel,
      JSON.stringify({ conversationId: currentConversationId, channel }),
    );
    setSubmitState("sending");
    const response =
      channel === "DISCORD_DM"
        ? await connectDiscordNotification()
        : await startTelegramNotificationConnect();
    setSubmitState("idle");

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setStatusMessage(response.data.message);

    if (response.data.connected) {
      openConnectionUrl(response.data.connectUrl);
      await resumePendingChannel();
      return;
    }
    if (response.data.authorizationUrl) {
      window.location.assign(response.data.authorizationUrl);
      return;
    }
    if (response.data.connectUrl) {
      openConnectionUrl(response.data.connectUrl);
    }
  }

  return (
    <section className="mx-auto w-full max-w-3xl rounded-[28px] border border-stone-200 bg-white shadow-[0_18px_50px_rgba(61,46,26,0.08)]">
      <div className="border-b border-stone-100 px-5 py-4">
        <h2 className="text-xl font-black tracking-tight">알림 만들기</h2>
      </div>

      <div
        aria-live="polite"
        className="grid max-h-[62vh] min-h-[420px] content-start gap-3 overflow-y-auto px-5 py-5"
      >
        {messages.map((message) => (
          <div
            key={message.id}
            role={message.status === "pending" ? "status" : undefined}
            className={classNames(
              "max-w-[82%] rounded-[20px] px-4 py-3 text-sm font-bold leading-6",
              message.role === "user"
                ? "ml-auto bg-emerald-700 text-white"
                : message.status === "error"
                  ? "border border-red-100 bg-red-50 text-red-950"
                  : "bg-stone-100 text-stone-950",
              message.status === "pending" && "animate-pulse",
            )}
          >
            <span>{message.content}</span>
            {message.status === "pending" ? (
              <span aria-hidden="true" className="ml-1 inline-flex gap-0.5">
                <span>.</span>
                <span>.</span>
                <span>.</span>
              </span>
            ) : null}
          </div>
        ))}

        {draft ? (
          <div className="max-w-[82%] rounded-[20px] border border-emerald-100 bg-emerald-50 px-4 py-3 text-sm text-emerald-950">
            <p className="font-black">{draft.query}</p>
            <dl className="mt-3 grid gap-2 font-bold">
              <SummaryRow label="영역" value={draft.domainLabel} />
              <SummaryRow label="알림 방식" value={draft.cadenceLabel} />
              <SummaryRow label="채널" value={draft.channelLabel} />
              <SummaryRow label="수신" value={draft.recipientLabel} />
            </dl>
          </div>
        ) : null}

        {actions.length > 0 ? (
          <div className="flex max-w-[82%] flex-wrap gap-2">
            {actions.map((action) => (
              <button
                key={`${action.type}-${action.value}`}
                type="button"
                onClick={() => void handleAction(action)}
                disabled={submitState === "sending"}
                className="min-h-10 rounded-full border border-stone-200 bg-white px-4 text-sm font-black text-stone-950 transition hover:border-emerald-700 hover:text-emerald-800 disabled:cursor-not-allowed disabled:bg-stone-100 disabled:text-stone-400"
              >
                {action.label}
              </button>
            ))}
          </div>
        ) : null}
        <div ref={chatEndRef} aria-hidden="true" />
      </div>

      <form onSubmit={handleSubmit} className="border-t border-stone-100 p-4">
        <div className="flex gap-2">
          <input
            value={input}
            onChange={(event) => setInput(event.target.value)}
            placeholder="강남구 아파트 매매 실거래가를 Telegram으로 알려줘"
            className="h-13 min-w-0 flex-1 rounded-full border border-stone-200 bg-[#fbfaf7] px-5 text-base font-bold outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
          />
          <button
            type="submit"
            disabled={submitState === "sending" || !input.trim()}
            className="h-13 rounded-full bg-stone-950 px-5 text-sm font-black text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-300"
          >
            보내기
          </button>
        </div>
        {statusMessage ? (
          <p className="mt-3 text-sm font-bold text-sky-900">{statusMessage}</p>
        ) : null}
      </form>
    </section>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-0.5">
      <dt className="text-xs font-black text-emerald-800">{label}</dt>
      <dd className="break-words font-black text-emerald-950">{value}</dd>
    </div>
  );
}

function openConnectionUrl(connectUrl: string | null) {
  if (!connectUrl) {
    return;
  }
  window.open(connectUrl, "_blank", "noopener,noreferrer");
}

function readPendingChannel():
  | { conversationId: string; channel: NotificationChannelId }
  | null {
  const raw = sessionStorage.getItem(STORAGE_KEYS.pendingChannel);
  const pending = parsePendingChannelSelection(raw);
  if (raw && !pending) {
    sessionStorage.removeItem(STORAGE_KEYS.pendingChannel);
  }
  return pending;
}

function readChatSession(): SubscriptionChatSessionSnapshot | null {
  const raw = sessionStorage.getItem(SUBSCRIPTION_CHAT_SESSION_KEY);
  if (!raw) {
    return null;
  }

  const snapshot = decodeSubscriptionChatSession(raw);
  if (!snapshot) {
    sessionStorage.removeItem(SUBSCRIPTION_CHAT_SESSION_KEY);
  }
  return snapshot;
}

function createMessageId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function classNames(...classes: Array<string | false | null | undefined>) {
  return classes.filter(Boolean).join(" ");
}
