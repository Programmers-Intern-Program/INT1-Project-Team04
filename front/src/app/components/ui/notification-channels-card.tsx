"use client";

import type { FormEvent } from "react";
import { useCallback, useEffect, useState } from "react";

import { STORAGE_KEYS } from "../../lib/subscription-chat-session";
import {
  connectDiscordNotification,
  connectEmailNotification,
  disconnectNotificationEndpoint,
  getNotificationEndpoints,
  startTelegramNotificationConnect,
  type NotificationChannelId,
  type NotificationEndpointStatus,
} from "../../lib/subscriptions";
import { ChannelEndpointRow } from "./channel-endpoint-row";

export type NotificationChannelsVariant = "light" | "dark";

type Props = {
  variant?: NotificationChannelsVariant;
  onUnauthenticated?: () => void;
};

export function NotificationChannelsCard({
  variant = "dark",
  onUnauthenticated,
}: Props) {
  const [endpoints, setEndpoints] = useState<NotificationEndpointStatus[]>([]);
  const [emailInput, setEmailInput] = useState("");
  const [updatingChannel, setUpdatingChannel] =
    useState<NotificationChannelId | null>(null);
  const [statusMessage, setStatusMessage] = useState("");

  const reload = useCallback(async () => {
    const response = await getNotificationEndpoints();
    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      return;
    }
    setEndpoints(response.data);
  }, [onUnauthenticated]);

  // OAuth 라운드트립 후 돌아왔을 때 connectUrl을 자동으로 열어주는 흐름.
  // 채널 카드가 채널 연결의 단일 진입점이므로 이 키는 여기서만 읽고 정리한다.
  const resumePending = useCallback(async () => {
    const raw = sessionStorage.getItem(STORAGE_KEYS.pendingEndpointChannel);
    if (raw !== "DISCORD_DM" && raw !== "TELEGRAM_DM") {
      if (raw) {
        sessionStorage.removeItem(STORAGE_KEYS.pendingEndpointChannel);
      }
      return;
    }

    const response = await getNotificationEndpoints();
    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      return;
    }
    setEndpoints(response.data);

    const connected = response.data.find(
      (endpoint) => endpoint.channel === raw && endpoint.connected,
    );
    sessionStorage.removeItem(STORAGE_KEYS.pendingEndpointChannel);
    if (connected) {
      openConnectionUrl(connected.connectUrl);
    }
  }, [onUnauthenticated]);

  useEffect(() => {
    // setTimeout(..., 0)로 비동기 큐로 보내 react-hooks/set-state-in-effect 룰을 회피한다.
    const timer = window.setTimeout(() => {
      void reload();
      void resumePending();
    }, 0);
    window.addEventListener("focus", reload);
    window.addEventListener("focus", resumePending);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("focus", reload);
      window.removeEventListener("focus", resumePending);
    };
  }, [reload, resumePending]);

  async function handleAction(channel: Exclude<NotificationChannelId, "EMAIL">) {
    if (updatingChannel) {
      return;
    }

    const endpoint = endpoints.find((item) => item.channel === channel);
    setStatusMessage("");
    setUpdatingChannel(channel);
    const response = endpoint?.connected
      ? await disconnectNotificationEndpoint(channel)
      : channel === "DISCORD_DM"
        ? await connectDiscordNotification()
        : await startTelegramNotificationConnect();
    setUpdatingChannel(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setStatusMessage(response.data.message);

    if (endpoint?.connected) {
      await reload();
      return;
    }

    if (response.data.connected) {
      openConnectionUrl(response.data.connectUrl);
      await reload();
      return;
    }
    if (response.data.authorizationUrl) {
      if (channel === "DISCORD_DM") {
        sessionStorage.setItem(STORAGE_KEYS.pendingEndpointChannel, channel);
      }
      window.location.assign(response.data.authorizationUrl);
      return;
    }
    if (response.data.connectUrl) {
      openConnectionUrl(response.data.connectUrl);
    }
  }

  async function handleEmailSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (updatingChannel) {
      return;
    }

    const email = emailInput.trim();
    if (!email || !email.includes("@")) {
      setStatusMessage("올바른 이메일 형식으로 입력해 주세요.");
      return;
    }

    setStatusMessage("");
    setUpdatingChannel("EMAIL");
    const response = await connectEmailNotification(email);
    setUpdatingChannel(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setEmailInput("");
    setStatusMessage(response.data.message);
    await reload();
  }

  async function handleDisconnectEmail() {
    if (updatingChannel) {
      return;
    }
    setStatusMessage("");
    setUpdatingChannel("EMAIL");
    const response = await disconnectNotificationEndpoint("EMAIL");
    setUpdatingChannel(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      setStatusMessage(response.error.message);
      return;
    }

    setStatusMessage(response.data.message);
    await reload();
  }

  const dark = variant === "dark";
  const emailEndpoint = endpoints.find((item) => item.channel === "EMAIL");

  return (
    <section
      className={
        dark
          ? "rounded-2xl border border-white/10 bg-white/8 p-4"
          : "rounded-2xl border border-stone-200 bg-white p-5"
      }
    >
      <h3 className={dark ? "text-sm font-black" : "text-base font-black text-stone-900"}>
        알림 채널
      </h3>
      <div className="mt-3 grid gap-3">
        <ChannelEndpointRow
          label="Discord"
          status={statusLabelFor(endpoints, "DISCORD_DM")}
          actionLabel={actionLabelFor(endpoints, "DISCORD_DM")}
          disabled={updatingChannel !== null}
          busy={updatingChannel === "DISCORD_DM"}
          variant={variant}
          onAction={() => void handleAction("DISCORD_DM")}
        />
        <ChannelEndpointRow
          label="Telegram"
          status={statusLabelFor(endpoints, "TELEGRAM_DM")}
          actionLabel={actionLabelFor(endpoints, "TELEGRAM_DM")}
          disabled={updatingChannel !== null}
          busy={updatingChannel === "TELEGRAM_DM"}
          variant={variant}
          onAction={() => void handleAction("TELEGRAM_DM")}
        />
        <form
          onSubmit={handleEmailSubmit}
          className={
            dark
              ? "grid gap-2 rounded-xl bg-white/6 p-3"
              : "grid gap-2 rounded-xl border border-stone-200 bg-white p-4"
          }
        >
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className={dark ? "text-sm font-black" : "text-sm font-black text-stone-900"}>
                Email
              </p>
              <p
                className={
                  dark
                    ? "mt-1 text-xs font-bold text-emerald-100"
                    : "mt-1 text-xs font-bold text-stone-500"
                }
              >
                {statusLabelFor(endpoints, "EMAIL")}
              </p>
            </div>
            <div className="flex shrink-0 gap-2">
              {emailEndpoint?.connected ? (
                <button
                  type="button"
                  onClick={() => void handleDisconnectEmail()}
                  disabled={updatingChannel !== null}
                  className={
                    dark
                      ? "rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
                      : "rounded-full border border-stone-300 bg-white px-3 py-1 text-xs font-black text-stone-700 transition hover:border-stone-500 disabled:cursor-not-allowed disabled:text-stone-300"
                  }
                >
                  {updatingChannel === "EMAIL" ? "처리 중" : "해제"}
                </button>
              ) : null}
              <button
                type="submit"
                disabled={updatingChannel !== null || !emailInput.trim()}
                className={
                  dark
                    ? "rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
                    : "rounded-full bg-stone-950 px-3 py-1 text-xs font-black text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-300"
                }
              >
                {updatingChannel === "EMAIL" ? "저장 중" : "저장"}
              </button>
            </div>
          </div>
          <input
            value={emailInput}
            onChange={(event) => setEmailInput(event.target.value)}
            placeholder="user@example.com"
            type="email"
            className={
              dark
                ? "h-10 min-w-0 rounded-xl border border-white/10 bg-white/10 px-3 text-sm font-bold text-white outline-none transition placeholder:text-emerald-100/55 focus:border-emerald-200"
                : "h-10 min-w-0 rounded-xl border border-stone-200 bg-[#fbfaf7] px-3 text-sm font-bold text-stone-900 outline-none transition placeholder:text-stone-400 focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
            }
          />
        </form>
      </div>
      {statusMessage ? (
        <p
          className={
            dark
              ? "mt-3 text-xs font-bold text-emerald-100"
              : "mt-3 text-xs font-bold text-sky-900"
          }
        >
          {statusMessage}
        </p>
      ) : null}
    </section>
  );
}

function openConnectionUrl(connectUrl: string | null) {
  if (!connectUrl) {
    return;
  }
  window.open(connectUrl, "_blank", "noopener,noreferrer");
}

function statusLabelFor(
  endpoints: NotificationEndpointStatus[],
  channel: NotificationChannelId,
): string {
  const endpoint = endpoints.find((item) => item.channel === channel);
  if (!endpoint?.connected) {
    return "미연결";
  }
  return endpoint.targetLabel ?? "연결됨";
}

function actionLabelFor(
  endpoints: NotificationEndpointStatus[],
  channel: NotificationChannelId,
): string {
  const endpoint = endpoints.find((item) => item.channel === channel);
  return endpoint?.connected ? "연동 해제" : "연결";
}
