"use client";

import type { FormEvent } from "react";
import { useCallback, useEffect, useRef, useState } from "react";

import {
  connectEmailNotification,
  connectDiscordNotification,
  getNotificationEndpoints,
  reconnectDiscordNotification,
  startTelegramNotificationConnect,
  type NotificationChannelId,
  type NotificationEndpointStatus,
} from "../lib/subscriptions";
import {
  STALE_CONVERSATION_MESSAGE,
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
  deleteSubscriptionSummary,
  getSubscriptionSummaries,
  sendConversationAction,
  sendConversationMessage,
  type ConversationActionOption,
  type ConversationResponse,
  type SubscriptionSummary,
} from "../lib/subscription-conversations";

type SubmitState = "idle" | "sending";

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
  const [notificationEndpoints, setNotificationEndpoints] = useState<NotificationEndpointStatus[]>([]);
  const [emailEndpointInput, setEmailEndpointInput] = useState("");
  const [submitState, setSubmitState] = useState<SubmitState>("idle");
  const [deletingSubscriptionId, setDeletingSubscriptionId] = useState<string | null>(null);
  const [updatingEndpointChannel, setUpdatingEndpointChannel] = useState<NotificationChannelId | null>(null);
  const [statusMessage, setStatusMessage] = useState("");
  const [hasRestoredSession, setHasRestoredSession] = useState(false);
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

  const reloadNotificationEndpoints = useCallback(async () => {
    const response = await getNotificationEndpoints();
    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      return;
    }
    setNotificationEndpoints(response.data);
  }, [onUnauthenticated]);

  const resetExpiredConversation = useCallback(() => {
    sessionStorage.removeItem(SUBSCRIPTION_CHAT_SESSION_KEY);
    sessionStorage.removeItem(PENDING_CHANNEL_KEY);
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
      if (shouldClearPendingChannelForResponse(readPendingChannel(), response)) {
        sessionStorage.removeItem(PENDING_CHANNEL_KEY);
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
    setNotificationEndpoints(endpointResult.data);

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
      if (isStaleConversationError(response.error, pending.conversationId)) {
        resetExpiredConversation();
        return;
      }
      setStatusMessage(response.error.message);
      return;
    }

    await applyConversationResponse(response.data);
  }, [applyConversationResponse, onUnauthenticated, resetExpiredConversation]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void reloadSubscriptions();
      void reloadNotificationEndpoints();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [reloadNotificationEndpoints, reloadSubscriptions]);

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
    window.addEventListener("focus", reloadNotificationEndpoints);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("focus", resumePendingChannel);
      window.removeEventListener("focus", reloadNotificationEndpoints);
    };
  }, [reloadNotificationEndpoints, resumePendingChannel]);

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
      appendUserMessage(action.label);
      await startChannelConnection(conversationId, action.value as NotificationChannelId);
      return;
    }

    const pendingChannel = pendingChannelSelectionForAction(conversationId, action);
    if (pendingChannel) {
      sessionStorage.setItem(PENDING_CHANNEL_KEY, JSON.stringify(pendingChannel));
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
        sessionStorage.removeItem(PENDING_CHANNEL_KEY);
      }
      replaceMessage(pendingMessageId, response.error.message, "error");
      setStatusMessage(response.error.message);
      return;
    }

    await applyConversationResponse(response.data, { replaceMessageId: pendingMessageId });
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
      await reloadNotificationEndpoints();
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

  async function handleEndpointChange(channel: Exclude<NotificationChannelId, "EMAIL">) {
    if (updatingEndpointChannel) {
      return;
    }

    setStatusMessage("");
    setUpdatingEndpointChannel(channel);
    const response =
      channel === "DISCORD_DM"
        ? await reconnectDiscordNotification()
        : await startTelegramNotificationConnect();
    setUpdatingEndpointChannel(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setStatusMessage(response.data.message);
    if (response.data.connected) {
      await reloadNotificationEndpoints();
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

  async function handleEmailEndpointSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (updatingEndpointChannel) {
      return;
    }

    const email = emailEndpointInput.trim();
    if (!email || !email.includes("@")) {
      setStatusMessage("올바른 이메일 형식으로 입력해 주세요.");
      return;
    }

    setStatusMessage("");
    setUpdatingEndpointChannel("EMAIL");
    const response = await connectEmailNotification(email);
    setUpdatingEndpointChannel(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setEmailEndpointInput("");
    setStatusMessage(response.data.message);
    await reloadNotificationEndpoints();
    await resumePendingChannel();
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

      <aside className="rounded-[28px] border border-stone-200 bg-[#10251d] p-5 text-white shadow-[0_18px_50px_rgba(16,37,29,0.18)]">
        <h2 className="text-lg font-black tracking-tight">구독중인 알림</h2>
        <div className="mt-4 grid gap-3">
          <section className="rounded-2xl border border-white/10 bg-white/8 p-4">
            <h3 className="text-sm font-black">알림 채널</h3>
            <div className="mt-3 grid gap-3">
              <ChannelEndpointRow
                label="Discord"
                status={endpointStatusLabel(notificationEndpoints, "DISCORD_DM")}
                hint="Discord 서버에 알림 봇을 초대한 뒤 다시 시도해 주세요."
                actionLabel={endpointActionLabel(notificationEndpoints, "DISCORD_DM")}
                disabled={updatingEndpointChannel !== null}
                busy={updatingEndpointChannel === "DISCORD_DM"}
                onAction={() => void handleEndpointChange("DISCORD_DM")}
              />
              <ChannelEndpointRow
                label="Telegram"
                status={endpointStatusLabel(notificationEndpoints, "TELEGRAM_DM")}
                actionLabel={endpointActionLabel(notificationEndpoints, "TELEGRAM_DM")}
                disabled={updatingEndpointChannel !== null}
                busy={updatingEndpointChannel === "TELEGRAM_DM"}
                onAction={() => void handleEndpointChange("TELEGRAM_DM")}
              />
              <form onSubmit={handleEmailEndpointSubmit} className="grid gap-2 rounded-xl bg-white/6 p-3">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-black">Email</p>
                    <p className="mt-1 text-xs font-bold text-emerald-100">
                      {endpointStatusLabel(notificationEndpoints, "EMAIL")}
                    </p>
                  </div>
                  <button
                    type="submit"
                    disabled={updatingEndpointChannel !== null || !emailEndpointInput.trim()}
                    className="rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
                  >
                    {updatingEndpointChannel === "EMAIL" ? "저장 중" : "저장"}
                  </button>
                </div>
                <input
                  value={emailEndpointInput}
                  onChange={(event) => setEmailEndpointInput(event.target.value)}
                  placeholder="user@example.com"
                  className="h-10 min-w-0 rounded-xl border border-white/10 bg-white/10 px-3 text-sm font-bold text-white outline-none transition placeholder:text-emerald-100/55 focus:border-emerald-200"
                />
              </form>
            </div>
          </section>
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
                  <div className="flex shrink-0 gap-2">
                    <button
                      type="button"
                      onClick={() => void handleDeleteSubscription(subscription.id)}
                      disabled={deletingSubscriptionId !== null}
                      aria-label={`${subscription.query} 알림 삭제`}
                      className="rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
                    >
                      {deletingSubscriptionId === subscription.id ? "삭제 중" : "삭제"}
                    </button>
                  </div>
                </div>
                <dl className="mt-3 grid gap-2 text-sm">
                  <SummaryRow label="영역" value={subscription.domainLabel} dark />
                  <SummaryRow label="알림 방식" value={subscription.cadenceLabel} dark />
                  <SummaryRow label="채널" value={subscription.channelLabel || "-"} dark />
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

function ChannelEndpointRow({
  label,
  status,
  hint,
  actionLabel,
  disabled,
  busy,
  onAction,
}: {
  label: string;
  status: string;
  hint?: string;
  actionLabel: string;
  disabled: boolean;
  busy: boolean;
  onAction: () => void;
}) {
  return (
    <div className="flex items-start justify-between gap-3 rounded-xl bg-white/6 p-3">
      <div>
        <p className="text-sm font-black">{label}</p>
        <p className="mt-1 text-xs font-bold text-emerald-100">{status}</p>
        {hint ? (
          <p className="mt-1 max-w-44 text-xs font-bold leading-5 text-emerald-50/80">
            {hint}
          </p>
        ) : null}
      </div>
      <button
        type="button"
        onClick={onAction}
        disabled={disabled}
        className="rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
      >
        {busy ? "처리 중" : actionLabel}
      </button>
    </div>
  );
}

function endpointStatusLabel(
  endpoints: NotificationEndpointStatus[],
  channel: NotificationChannelId,
): string {
  const endpoint = endpoints.find((item) => item.channel === channel);
  if (!endpoint?.connected) {
    return "미연결";
  }
  return endpoint.targetLabel ?? "연결됨";
}

function endpointActionLabel(
  endpoints: NotificationEndpointStatus[],
  channel: NotificationChannelId,
): string {
  const endpoint = endpoints.find((item) => item.channel === channel);
  return endpoint?.connected ? "변경" : "연결";
}

function readPendingChannel():
  | { conversationId: string; channel: NotificationChannelId }
  | null {
  const raw = sessionStorage.getItem(PENDING_CHANNEL_KEY);
  const pending = parsePendingChannelSelection(raw);
  if (raw && !pending) {
    sessionStorage.removeItem(PENDING_CHANNEL_KEY);
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
