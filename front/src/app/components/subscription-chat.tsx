"use client";

import type { FormEvent } from "react";
import { useCallback, useEffect, useRef, useState } from "react";

import {
  connectDiscordNotification,
  getNotificationEndpoints,
  startTelegramNotificationConnect,
  type NotificationChannelId,
} from "../lib/subscriptions";
import {
  deleteSubscriptionSummary,
  getSubscriptionSummaries,
  sendConversationAction,
  sendConversationMessage,
  type ConversationActionOption,
  type ConversationResponse,
  type SubscriptionSummary,
} from "../lib/subscription-conversations";

type ChatMessage = {
  id: string;
  role: "assistant" | "user";
  content: string;
  status?: "pending" | "error";
};

type SubmitState = "idle" | "sending";

type DebugJsonSnapshot = {
  request: string;
  response: string;
};

const PENDING_CHANNEL_KEY = "subscription-chat-pending-channel";

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
  const [subscriptions, setSubscriptions] = useState<SubscriptionSummary[]>([]);
  const [submitState, setSubmitState] = useState<SubmitState>("idle");
  const [deletingSubscriptionId, setDeletingSubscriptionId] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState("");
  const [debugJson, setDebugJson] = useState<DebugJsonSnapshot>({
    request: "{}",
    response: "{}",
  });
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  const reloadSubscriptions = useCallback(async () => {
    const response = await getSubscriptionSummaries();
    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }
    setSubscriptions(response.data);
  }, [onUnauthenticated]);

  const applyConversationResponse = useCallback(
    async (
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

      if (response.status === "CREATED") {
        await reloadSubscriptions();
      }
    },
    [reloadSubscriptions],
  );

  const resumePendingChannel = useCallback(async () => {
    const pending = readPendingChannel();
    if (!pending) {
      return;
    }

    const endpointResult = await getNotificationEndpoints();
    if (!endpointResult.ok) {
      if (endpointResult.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      return;
    }

    const connected = endpointResult.data.some(
      (endpoint) => endpoint.channel === pending.channel && endpoint.connected,
    );
    if (!connected) {
      return;
    }

    sessionStorage.removeItem(PENDING_CHANNEL_KEY);
    const response = await sendConversationAction({
      conversationId: pending.conversationId,
      action: { type: "SELECT_CHANNEL", value: pending.channel },
    });

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    await applyConversationResponse(response.data);
  }, [applyConversationResponse, onUnauthenticated]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void reloadSubscriptions();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [reloadSubscriptions]);

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
    setDebugJson({
      request: formatDebugJson(requestPayload),
      response: formatDebugJson({ pending: true }),
    });
    const response = await sendConversationMessage(requestPayload);
    setSubmitState("idle");
    setDebugJson((current) => ({
      ...current,
      response: formatDebugJson(response),
    }));

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      replaceMessage(pendingMessageId, response.error.message, "error");
      setStatusMessage(response.error.message);
      return;
    }

    await applyConversationResponse(response.data, { replaceMessageId: pendingMessageId });
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
      await startChannelConnection(conversationId, action.value as NotificationChannelId);
      return;
    }

    setSubmitState("sending");
    const pendingMessageId = createMessageId("assistant-pending");
    setMessages((current) => [
      ...current,
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
    setDebugJson({
      request: formatDebugJson(requestPayload),
      response: formatDebugJson({ pending: true }),
    });
    const response = await sendConversationAction(requestPayload);
    setSubmitState("idle");
    setDebugJson((current) => ({
      ...current,
      response: formatDebugJson(response),
    }));

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      replaceMessage(pendingMessageId, response.error.message, "error");
      setStatusMessage(response.error.message);
      return;
    }

    await applyConversationResponse(response.data, { replaceMessageId: pendingMessageId });
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
      PENDING_CHANNEL_KEY,
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
      await resumePendingChannel();
      return;
    }
    if (response.data.authorizationUrl) {
      window.location.assign(response.data.authorizationUrl);
      return;
    }
    if (response.data.connectUrl) {
      window.open(response.data.connectUrl, "_blank", "noopener,noreferrer");
    }
  }

  async function handleDeleteSubscription(subscriptionId: string) {
    if (deletingSubscriptionId) {
      return;
    }

    setStatusMessage("");
    setDeletingSubscriptionId(subscriptionId);
    const response = await deleteSubscriptionSummary(subscriptionId);
    setDeletingSubscriptionId(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setStatusMessage("알림 구독을 삭제했어요.");
    await reloadSubscriptions();
  }

  return (
    <section className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
      <section className="min-w-0 rounded-[28px] border border-stone-200 bg-white shadow-[0_18px_50px_rgba(61,46,26,0.08)]">
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
                <SummaryRow label="주기" value={draft.cadenceLabel} />
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
              placeholder="강남구 아파트 매매 실거래가를 매일 아침 Telegram으로 알려줘"
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
          <div className="mt-4 grid gap-3 rounded-2xl border border-dashed border-stone-300 bg-stone-50 p-3 text-xs text-stone-800 md:grid-cols-2">
            <label className="grid gap-2 font-black">
              요청 JSON
              <textarea
                readOnly
                value={debugJson.request}
                className="min-h-40 resize-y rounded-xl border border-stone-200 bg-white p-3 text-xs leading-5 text-stone-900 outline-none"
              />
            </label>
            <label className="grid gap-2 font-black">
              응답 JSON
              <textarea
                readOnly
                value={debugJson.response}
                className="min-h-40 resize-y rounded-xl border border-stone-200 bg-white p-3 text-xs leading-5 text-stone-900 outline-none"
              />
            </label>
          </div>
        </form>
      </section>

      <aside className="rounded-[28px] border border-stone-200 bg-[#10251d] p-5 text-white shadow-[0_18px_50px_rgba(16,37,29,0.18)]">
        <h2 className="text-lg font-black tracking-tight">구독중인 알림</h2>
        <div className="mt-4 grid gap-3">
          {subscriptions.length === 0 ? (
            <p className="rounded-2xl bg-white/8 p-4 text-sm font-bold text-emerald-50">
              아직 시작한 알림이 없습니다.
            </p>
          ) : (
            subscriptions.map((subscription) => (
              <article
                key={subscription.id}
                className="rounded-2xl border border-white/10 bg-white/8 p-4"
              >
                <div className="flex items-start justify-between gap-3">
                  <h3 className="min-w-0 text-sm font-black leading-6">
                    {subscription.query}
                  </h3>
                  <button
                    type="button"
                    onClick={() => void handleDeleteSubscription(subscription.id)}
                    disabled={deletingSubscriptionId !== null}
                    aria-label={`${subscription.query} 알림 삭제`}
                    className="shrink-0 rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
                  >
                    {deletingSubscriptionId === subscription.id ? "삭제 중" : "삭제"}
                  </button>
                </div>
                <dl className="mt-3 grid gap-2 text-sm">
                  <SummaryRow label="영역" value={subscription.domainLabel} dark />
                  <SummaryRow label="주기" value={subscription.cadenceLabel} dark />
                  <SummaryRow label="채널" value={subscription.channelLabel || "-"} dark />
                  <SummaryRow
                    label="다음 확인"
                    value={formatDateTime(subscription.nextRun)}
                    dark
                  />
                </dl>
              </article>
            ))
          )}
        </div>
      </aside>
    </section>
  );
}

function SummaryRow({
  label,
  value,
  dark = false,
}: {
  label: string;
  value: string;
  dark?: boolean;
}) {
  return (
    <div className="grid gap-0.5">
      <dt className={classNames("text-xs font-black", dark ? "text-emerald-100" : "text-emerald-800")}>
        {label}
      </dt>
      <dd className={classNames("break-words font-black", dark ? "text-white" : "text-emerald-950")}>
        {value}
      </dd>
    </div>
  );
}

function readPendingChannel():
  | { conversationId: string; channel: NotificationChannelId }
  | null {
  const raw = sessionStorage.getItem(PENDING_CHANNEL_KEY);
  if (!raw) {
    return null;
  }

  try {
    const value = JSON.parse(raw) as {
      conversationId?: unknown;
      channel?: unknown;
    };
    if (
      typeof value.conversationId === "string" &&
      (value.channel === "TELEGRAM_DM" || value.channel === "DISCORD_DM")
    ) {
      return {
        conversationId: value.conversationId,
        channel: value.channel,
      };
    }
  } catch {
    sessionStorage.removeItem(PENDING_CHANNEL_KEY);
  }

  return null;
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function createMessageId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function formatDebugJson(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

function classNames(...classes: Array<string | false | null | undefined>) {
  return classes.filter(Boolean).join(" ");
}
