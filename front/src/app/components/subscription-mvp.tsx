"use client";

import type { FormEvent } from "react";
import { useEffect, useMemo, useState } from "react";

import {
  buildSubscriptionPayload,
  CADENCE_PRESETS,
  CHANNEL_PRESETS,
  connectDiscordNotification,
  createSubscription,
  disconnectNotificationEndpoint,
  getDomains,
  getNotificationEndpoints,
  shouldShowEmailAddressInput,
  startTelegramNotificationConnect,
  validateSubscriptionForm,
  type CreateSubscriptionResult,
  type DomainPreset,
  type NotificationEndpointStatus,
  type SubscriptionFormState,
} from "../lib/subscriptions";

type SubmitState = "idle" | "submitting";
type DomainState = "loading" | "ready" | "error";
type ConnectState = SubscriptionFormState["notificationChannel"] | null;

const DEFAULT_FORM: SubscriptionFormState = {
  query: "강남 투룸 전세 시세 바뀌면 알려줘",
  selectedDomainId: 0,
  cadenceId: "hourly",
  notificationChannel: "EMAIL",
  notificationTargetAddress: "",
};

export function SubscriptionMvp({
  onUnauthenticated,
}: {
  onUnauthenticated?: () => void;
}) {
  const [form, setForm] = useState<SubscriptionFormState>(DEFAULT_FORM);
  const [domains, setDomains] = useState<DomainPreset[]>([]);
  const [domainState, setDomainState] = useState<DomainState>("loading");
  const [domainMessage, setDomainMessage] = useState("");
  const [endpointStatuses, setEndpointStatuses] = useState<
    NotificationEndpointStatus[]
  >([]);
  const [endpointMessage, setEndpointMessage] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [result, setResult] = useState<CreateSubscriptionResult | null>(null);
  const [submitState, setSubmitState] = useState<SubmitState>("idle");
  const [connectState, setConnectState] = useState<ConnectState>(null);

  useEffect(() => {
    let active = true;

    async function loadDomains() {
      const response = await getDomains();
      if (!active) {
        return;
      }

      if (!response.ok) {
        setDomains([]);
        setDomainState("error");
        setDomainMessage(response.error.message);
        return;
      }

      if (response.data.length === 0) {
        setDomains([]);
        setDomainState("error");
        setDomainMessage("감시 영역이 아직 준비되지 않았습니다.");
        return;
      }

      const firstDomain = response.data[0];
      setDomains(response.data);
      setDomainState("ready");
      setDomainMessage("");
      setForm((current) => {
        const selectedDomain =
          response.data.find((domain) => domain.id === current.selectedDomainId) ??
          firstDomain;

        return {
          ...current,
          selectedDomainId: selectedDomain.id,
          query: current.query.trim() ? current.query : selectedDomain.example,
        };
      });
    }

    loadDomains();

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;

    async function loadNotificationEndpoints() {
      const response = await getNotificationEndpoints();
      if (!active) {
        return;
      }

      if (!response.ok) {
        setEndpointStatuses([]);
        setEndpointMessage(response.error.message);
        if (response.error.code === "UNAUTHENTICATED") {
          onUnauthenticated?.();
        }
        return;
      }

      setEndpointStatuses(response.data);
      setEndpointMessage("");
    }

    loadNotificationEndpoints();
    window.addEventListener("focus", loadNotificationEndpoints);

    return () => {
      active = false;
      window.removeEventListener("focus", loadNotificationEndpoints);
    };
  }, [onUnauthenticated]);

  const selectedDomain = useMemo(
    () =>
      domains.find((domain) => domain.id === form.selectedDomainId) ?? domains[0],
    [domains, form.selectedDomainId],
  );
  const selectedCadence = useMemo(
    () =>
      CADENCE_PRESETS.find((cadence) => cadence.id === form.cadenceId) ??
      CADENCE_PRESETS[0],
    [form.cadenceId],
  );
  const selectedChannel = useMemo(
    () =>
      CHANNEL_PRESETS.find((channel) => channel.id === form.notificationChannel) ??
      CHANNEL_PRESETS[0],
    [form.notificationChannel],
  );
  const selectedEndpoint = useMemo(
    () =>
      endpointStatuses.find(
        (endpoint) => endpoint.channel === form.notificationChannel,
      ),
    [endpointStatuses, form.notificationChannel],
  );

  const isSelectedEndpointConnected = selectedEndpoint?.connected ?? false;
  const selectedRecipient =
    form.notificationChannel === "EMAIL"
      ? form.notificationTargetAddress.trim() || selectedEndpoint?.targetLabel || ""
      : selectedEndpoint?.targetLabel || "";
  const isChannelReady =
    form.notificationChannel === "EMAIL"
      ? Boolean(form.notificationTargetAddress.trim() || isSelectedEndpointConnected)
      : isSelectedEndpointConnected;
  const showEmailAddressInput = shouldShowEmailAddressInput(
    form.notificationChannel,
    isSelectedEndpointConnected,
  );

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setResult(null);

    const nextErrors = validateSubscriptionForm(form, {
      selectedEndpointConnected: isSelectedEndpointConnected,
    });

    if (form.notificationChannel !== "EMAIL" && !isSelectedEndpointConnected) {
      nextErrors.notificationTargetAddress = `${selectedChannel.label} 연결이 필요합니다.`;
    }

    setErrors(nextErrors);

    if (Object.keys(nextErrors).length > 0) {
      return;
    }

    setSubmitState("submitting");
    const response = await createSubscription(buildSubscriptionPayload(form));
    if (!response.ok && response.error.code === "UNAUTHENTICATED") {
      onUnauthenticated?.();
    }
    if (response.ok) {
      await reloadNotificationEndpoints();
    }
    setResult(response);
    setSubmitState("idle");
  }

  async function handleConnectChannel() {
    if (form.notificationChannel === "EMAIL") {
      return;
    }

    setConnectState(form.notificationChannel);
    setEndpointMessage("");

    const response =
      form.notificationChannel === "DISCORD_DM"
        ? await connectDiscordNotification()
        : await startTelegramNotificationConnect();

    if (!response.ok) {
      setEndpointMessage(response.error.message);
      setConnectState(null);
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      return;
    }

    setEndpointMessage(response.data.message);

    if (response.data.authorizationUrl) {
      window.location.assign(response.data.authorizationUrl);
      return;
    }

    if (response.data.connectUrl) {
      window.open(response.data.connectUrl, "_blank", "noopener,noreferrer");
    }

    if (response.data.connected) {
      await reloadNotificationEndpoints();
    }

    setConnectState(null);
  }

  async function handleDisconnectChannel() {
    setConnectState(form.notificationChannel);
    setEndpointMessage("");

    const response = await disconnectNotificationEndpoint(form.notificationChannel);
    if (!response.ok) {
      setEndpointMessage(response.error.message);
      setConnectState(null);
      if (response.error.code === "UNAUTHENTICATED") {
        onUnauthenticated?.();
      }
      return;
    }

    setEndpointMessage(response.data.message);
    await reloadNotificationEndpoints();
    setConnectState(null);
  }

  async function reloadNotificationEndpoints() {
    const statuses = await getNotificationEndpoints();
    if (statuses.ok) {
      setEndpointStatuses(statuses.data);
    }
  }

  return (
    <section className="text-stone-950">
      <form
        onSubmit={handleSubmit}
        className="grid gap-4 xl:grid-cols-[260px_minmax(0,1fr)_300px]"
      >
        <aside className="rounded-[28px] border border-stone-200 bg-[#fffaf0] p-4 shadow-[0_18px_50px_rgba(61,46,26,0.08)]">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-black tracking-tight">관심사</h2>
            <span className="rounded-full bg-emerald-100 px-3 py-1 text-xs font-black text-emerald-800">
              선택
            </span>
          </div>

          <div className="grid grid-cols-2 gap-2 xl:grid-cols-1">
            {domains.map((domain) => {
              const isActive = selectedDomain?.id === domain.id;

              return (
                <button
                  key={domain.id}
                  type="button"
                  aria-pressed={isActive}
                  onClick={() => {
                    setForm((current) => ({
                      ...current,
                      selectedDomainId: domain.id,
                      query: current.query.trim() ? current.query : domain.example,
                    }));
                    setErrors((current) => ({ ...current, domainId: "" }));
                  }}
                  className={classNames(
                    "min-h-14 rounded-2xl border px-4 py-3 text-left text-sm font-black transition",
                    isActive
                      ? "border-emerald-700 bg-emerald-700 text-white shadow-[0_10px_24px_rgba(4,120,87,0.22)]"
                      : "border-stone-200 bg-white text-stone-800 hover:border-stone-400",
                  )}
                >
                  {domain.label}
                </button>
              );
            })}
          </div>

          {domainState === "loading" ? (
            <p className="mt-4 text-sm font-bold text-stone-500">불러오는 중</p>
          ) : null}
          {errors.domainId ? (
            <p role="alert" className="mt-4 text-sm font-bold text-red-700">
              {errors.domainId}
            </p>
          ) : null}
          {domainState === "error" ? (
            <p role="alert" className="mt-4 text-sm font-bold text-red-700">
              {domainMessage}
            </p>
          ) : null}
        </aside>

        <section className="min-w-0 rounded-[32px] border border-stone-200 bg-white p-5 shadow-[0_18px_50px_rgba(61,46,26,0.08)] md:p-7">
          <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm font-black text-emerald-700">
                알림 만들기
              </p>
              <h2 className="mt-1 text-3xl font-black tracking-tight">
                어떤 변화를 지켜볼까요?
              </h2>
            </div>
            <span
              className={classNames(
                "rounded-full px-3 py-1 text-xs font-black",
                isChannelReady
                  ? "bg-emerald-100 text-emerald-800"
                  : "bg-amber-100 text-amber-800",
              )}
            >
              {isChannelReady ? "준비됨" : "연결 필요"}
            </span>
          </div>

          <div className="grid gap-5">
            <div className="grid gap-2">
              <label htmlFor="query" className="text-sm font-black text-stone-900">
                요청
              </label>
              <textarea
                id="query"
                value={form.query}
                onChange={(event) => {
                  setForm((current) => ({
                    ...current,
                    query: event.target.value,
                  }));
                  setErrors((current) => ({ ...current, query: "" }));
                }}
                rows={7}
                className="min-h-48 resize-none rounded-[24px] border border-stone-200 bg-[#fbfaf7] px-5 py-4 text-lg leading-8 outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
              />
              {errors.query ? (
                <p role="alert" className="text-sm font-bold text-red-700">
                  {errors.query}
                </p>
              ) : null}
            </div>

            <div className="grid gap-5 lg:grid-cols-2">
              <fieldset className="grid gap-3">
                <legend className="mb-3 text-sm font-black text-stone-900">
                  주기
                </legend>
                <div className="grid gap-2">
                  {CADENCE_PRESETS.map((cadence) => {
                    const isActive = cadence.id === form.cadenceId;

                    return (
                      <button
                        key={cadence.id}
                        type="button"
                        aria-pressed={isActive}
                        onClick={() =>
                          setForm((current) => ({
                            ...current,
                            cadenceId: cadence.id,
                          }))
                        }
                        className={classNames(
                          "rounded-2xl border px-4 py-4 text-left text-sm font-black transition",
                          isActive
                            ? "border-stone-950 bg-stone-950 text-white shadow-[0_10px_24px_rgba(28,25,23,0.18)]"
                            : "border-stone-200 bg-[#fbfaf7] text-stone-800 hover:border-stone-400 hover:bg-white",
                        )}
                      >
                        {cadence.label}
                      </button>
                    );
                  })}
                </div>
              </fieldset>

              <fieldset className="grid gap-3">
                <legend className="mb-3 text-sm font-black text-stone-900">
                  채널
                </legend>
                <div className="grid grid-cols-3 gap-2">
                  {CHANNEL_PRESETS.map((channel) => {
                    const isActive = channel.id === form.notificationChannel;

                    return (
                      <button
                        key={channel.id}
                        type="button"
                        aria-pressed={isActive}
                        onClick={() => {
                          setForm((current) => ({
                            ...current,
                            notificationChannel: channel.id,
                            notificationTargetAddress:
                              channel.id === "EMAIL"
                                ? current.notificationTargetAddress
                                : "",
                          }));
                          setErrors((current) => ({
                            ...current,
                            notificationTargetAddress: "",
                          }));
                          setEndpointMessage("");
                        }}
                        className={classNames(
                          "rounded-2xl border px-3 py-4 text-center text-sm font-black transition",
                          isActive
                            ? "border-sky-700 bg-sky-700 text-white shadow-[0_10px_24px_rgba(3,105,161,0.20)]"
                            : "border-stone-200 bg-[#fbfaf7] text-stone-800 hover:border-stone-400 hover:bg-white",
                        )}
                      >
                        {channel.label}
                      </button>
                    );
                  })}
                </div>

                {form.notificationChannel === "EMAIL" ? (
                  <div className="grid gap-2">
                    {showEmailAddressInput ? (
                      <>
                        <label
                          htmlFor="notificationTargetAddress"
                          className="text-sm font-black text-stone-900"
                        >
                          {selectedChannel.targetLabel}
                        </label>
                        <input
                          id="notificationTargetAddress"
                          type="email"
                          value={form.notificationTargetAddress}
                          placeholder={selectedChannel.targetPlaceholder}
                          onChange={(event) => {
                            setForm((current) => ({
                              ...current,
                              notificationTargetAddress: event.target.value,
                            }));
                            setErrors((current) => ({
                              ...current,
                              notificationTargetAddress: "",
                            }));
                          }}
                          className="h-13 rounded-2xl border border-stone-200 bg-[#fbfaf7] px-4 text-base outline-none transition focus:border-sky-700 focus:bg-white focus:ring-4 focus:ring-sky-100"
                        />
                      </>
                    ) : (
                      <div className="rounded-2xl border border-emerald-100 bg-emerald-50 p-4">
                        <p className="text-sm font-black text-emerald-950">
                          이메일 연결됨
                        </p>
                        <p className="mt-1 break-all text-sm font-bold text-emerald-800">
                          {selectedEndpoint?.targetLabel}
                        </p>
                      </div>
                    )}
                    {isSelectedEndpointConnected ? (
                      <button
                        type="button"
                        onClick={handleDisconnectChannel}
                        disabled={connectState === form.notificationChannel}
                        className="h-11 rounded-2xl border border-stone-200 bg-white px-4 text-sm font-black text-stone-900 transition hover:bg-stone-50 disabled:cursor-not-allowed disabled:bg-stone-100 disabled:text-stone-400"
                      >
                        {connectState === form.notificationChannel
                          ? "해제 중"
                          : "연결 해제"}
                      </button>
                    ) : null}
                  </div>
                ) : (
                  <div className="grid gap-2">
                    <button
                      type="button"
                      onClick={
                        isSelectedEndpointConnected
                          ? handleDisconnectChannel
                          : handleConnectChannel
                      }
                      disabled={connectState === form.notificationChannel}
                      className={classNames(
                        "h-13 rounded-2xl px-4 text-sm font-black transition disabled:cursor-not-allowed disabled:bg-stone-300 disabled:text-stone-500",
                        isSelectedEndpointConnected
                          ? "border border-sky-200 bg-white text-sky-900 hover:bg-sky-50"
                          : "bg-sky-700 text-white hover:bg-sky-800",
                      )}
                    >
                      {connectState === form.notificationChannel
                        ? isSelectedEndpointConnected
                          ? "해제 중"
                          : "연결 중"
                        : isSelectedEndpointConnected
                          ? "연결 해제"
                          : selectedChannel.actionLabel}
                    </button>
                  </div>
                )}

                {endpointMessage ? (
                  <p className="text-sm font-bold leading-6 text-sky-900">
                    {endpointMessage}
                  </p>
                ) : null}
                {errors.notificationTargetAddress ? (
                  <p role="alert" className="text-sm font-bold text-red-700">
                    {errors.notificationTargetAddress}
                  </p>
                ) : null}
              </fieldset>
            </div>

            <button
              type="submit"
              disabled={submitState === "submitting" || domainState !== "ready"}
              className="h-14 rounded-2xl bg-emerald-700 px-5 text-base font-black text-white transition hover:bg-emerald-800 disabled:cursor-not-allowed disabled:bg-stone-300"
            >
              {submitState === "submitting" ? "등록 중" : "알림 시작"}
            </button>
          </div>
        </section>

        <aside className="grid content-start gap-4">
          <section className="rounded-[28px] border border-stone-200 bg-[#10251d] p-5 text-white shadow-[0_18px_50px_rgba(16,37,29,0.18)]">
            <h2 className="text-lg font-black tracking-tight">요약</h2>
            <dl className="mt-5 grid gap-4 text-sm">
              <SummaryRow label="관심사" value={selectedDomain?.label ?? "-"} />
              <SummaryRow label="주기" value={selectedCadence.label} />
              <SummaryRow label="채널" value={selectedChannel.label} />
              <SummaryRow
                label="수신"
                value={selectedRecipient ? maskContact(selectedRecipient) : "미지정"}
              />
            </dl>
          </section>

          {result ? (
            result.ok ? (
              <section className="rounded-[28px] border border-emerald-200 bg-emerald-50 p-5 shadow-sm">
                <p className="text-base font-black text-emerald-950">
                  알림이 시작됐어요
                </p>
                <dl className="mt-4 grid gap-3 text-sm">
                  <SummaryRow
                    label="다음 확인"
                    value={formatDateTime(result.data.nextRun)}
                    tone="dark"
                  />
                </dl>
              </section>
            ) : (
              <section
                role="alert"
                className="rounded-[28px] border border-red-200 bg-red-50 p-5 shadow-sm"
              >
                <p className="text-base font-black text-red-950">
                  등록하지 못했어요
                </p>
                <p className="mt-2 text-sm font-bold leading-6 text-red-800">
                  {result.error.message}
                </p>
              </section>
            )
          ) : null}
        </aside>
      </form>
    </section>
  );
}

function SummaryRow({
  label,
  value,
  tone = "light",
}: {
  label: string;
  value: string;
  tone?: "light" | "dark";
}) {
  return (
    <div className="grid gap-1">
      <dt
        className={classNames(
          "text-xs font-black",
          tone === "light" ? "text-emerald-100" : "text-emerald-800",
        )}
      >
        {label}
      </dt>
      <dd
        className={classNames(
          "break-words text-base font-black",
          tone === "light" ? "text-white" : "text-emerald-950",
        )}
      >
        {value}
      </dd>
    </div>
  );
}

function formatDateTime(value: string): string {
  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function maskContact(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return "미지정";
  }

  if (!trimmed.includes("@")) {
    return "연결됨";
  }

  const [localPart, domain] = trimmed.split("@");
  const first = localPart.at(0) ?? "";
  return `${first}***@${domain}`;
}

function classNames(...classes: Array<string | false | null | undefined>) {
  return classes.filter(Boolean).join(" ");
}
