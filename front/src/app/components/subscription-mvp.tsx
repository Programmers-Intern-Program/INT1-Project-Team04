"use client";

import type { FormEvent } from "react";
import { useEffect, useMemo, useState } from "react";

import {
  buildSubscriptionPayload,
  CADENCE_PRESETS,
  CHANNEL_PRESETS,
  createSubscription,
  getDomains,
  validateSubscriptionForm,
  type CreateSubscriptionResult,
  type DomainPreset,
  type SubscriptionFormState,
} from "../lib/subscriptions";

type SubmitState = "idle" | "submitting";
type DomainState = "loading" | "ready" | "error";

const DEFAULT_FORM: SubscriptionFormState = {
  query: "강남 투룸 전세 시세 바뀌면 알려줘",
  selectedDomainId: 0,
  cadenceId: "hourly",
  notificationChannel: "DISCORD_DM",
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
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [result, setResult] = useState<CreateSubscriptionResult | null>(null);
  const [submitState, setSubmitState] = useState<SubmitState>("idle");

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
  const previewPayload = useMemo(
    () => (selectedDomain ? buildSubscriptionPayload(form) : null),
    [form, selectedDomain],
  );

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setResult(null);

    const nextErrors = validateSubscriptionForm(form);
    setErrors(nextErrors);

    if (Object.keys(nextErrors).length > 0) {
      return;
    }

    setSubmitState("submitting");
    const response = await createSubscription(buildSubscriptionPayload(form));
    if (!response.ok && response.error.code === "UNAUTHENTICATED") {
      onUnauthenticated?.();
    }
    setResult(response);
    setSubmitState("idle");
  }

  return (
    <section className="text-zinc-950">
      <div className="grid gap-4 xl:grid-cols-[220px_minmax(0,1fr)]">
        <aside className="flex flex-col gap-4 rounded-lg border border-zinc-200 bg-white p-5 shadow-sm">
          <div className="space-y-3">
            <p className="text-sm font-semibold text-emerald-700">
              관심사 알림
            </p>
            <div>
              <h2 className="text-2xl font-bold tracking-normal text-zinc-950">
                감시 영역
              </h2>
            </div>
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
                    "min-h-14 rounded-lg border px-4 py-3 text-left transition",
                    isActive
                      ? "border-emerald-700 bg-emerald-50 text-emerald-950"
                      : "border-zinc-200 bg-zinc-50 text-zinc-800 hover:border-zinc-300 hover:bg-white",
                  )}
                >
                  <span className="block text-sm font-semibold">
                    {domain.label}
                  </span>
                </button>
              );
            })}
          </div>
          {domainState === "loading" ? (
            <p className="text-sm font-medium text-zinc-500">
              감시 영역을 불러오는 중...
            </p>
          ) : null}
          {errors.domainId ? (
            <p role="alert" className="text-sm font-medium text-red-700">
              {errors.domainId}
            </p>
          ) : null}
          {domainState === "error" ? (
            <p role="alert" className="text-sm font-medium text-red-700">
              {domainMessage}
            </p>
          ) : null}
        </aside>

        <section className="min-w-0 rounded-lg border border-zinc-200 bg-white p-5 shadow-sm md:p-6">
          <form onSubmit={handleSubmit} className="flex h-full flex-col gap-6">
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-zinc-500">
                    알림 조건 등록
                  </p>
                  <h2 className="mt-1 text-2xl font-bold tracking-normal text-zinc-950">
                    어떤 변화를 지켜볼까요?
                  </h2>
                </div>
              </div>

              <label
                htmlFor="query"
                className="text-sm font-semibold text-zinc-800"
              >
                요청 내용
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
                rows={6}
                className="min-h-40 resize-none rounded-lg border border-zinc-300 bg-zinc-50 px-4 py-3 text-base leading-7 outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
              />
              {errors.query ? (
                <p role="alert" className="text-sm font-medium text-red-700">
                  {errors.query}
                </p>
              ) : null}
            </div>

            <div className="grid gap-4 xl:grid-cols-2">
              <fieldset className="space-y-3">
                <legend className="text-sm font-semibold text-zinc-800">
                  감시 주기
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
                          "rounded-lg border px-4 py-3 text-left transition",
                          isActive
                            ? "border-zinc-950 bg-zinc-950 text-white"
                            : "border-zinc-200 bg-zinc-50 text-zinc-800 hover:border-zinc-300 hover:bg-white",
                        )}
                      >
                        <span className="block text-sm font-semibold">
                          {cadence.label}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </fieldset>

              <fieldset className="space-y-3">
                <legend className="text-sm font-semibold text-zinc-800">
                  알림 채널
                </legend>
                <div className="grid gap-2">
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
                          }));
                          setErrors((current) => ({
                            ...current,
                            notificationTargetAddress: "",
                          }));
                        }}
                        className={classNames(
                          "rounded-lg border px-4 py-3 text-left transition",
                          isActive
                            ? "border-sky-700 bg-sky-50 text-sky-950"
                            : "border-zinc-200 bg-zinc-50 text-zinc-800 hover:border-zinc-300 hover:bg-white",
                        )}
                      >
                        <span className="block text-sm font-semibold">
                          {channel.label}
                        </span>
                      </button>
                    );
                  })}
                </div>
                <label
                  htmlFor="notificationTargetAddress"
                  className="block text-sm font-semibold text-zinc-800"
                >
                  {selectedChannel.targetLabel}
                </label>
                <input
                  id="notificationTargetAddress"
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
                  className="h-11 w-full rounded-lg border border-zinc-300 bg-zinc-50 px-4 text-sm outline-none transition focus:border-sky-700 focus:bg-white focus:ring-4 focus:ring-sky-100"
                />
                {errors.notificationTargetAddress ? (
                  <p role="alert" className="text-sm font-medium text-red-700">
                    {errors.notificationTargetAddress}
                  </p>
                ) : null}
              </fieldset>
            </div>

            <button
              type="submit"
              disabled={submitState === "submitting" || domainState !== "ready"}
              className="mt-auto h-12 rounded-lg bg-emerald-700 px-5 text-sm font-bold text-white transition hover:bg-emerald-800 disabled:cursor-not-allowed disabled:bg-zinc-400"
            >
              {submitState === "submitting" ? "등록 중..." : "알림 등록"}
            </button>
          </form>
        </section>

        <aside className="grid gap-4 md:grid-cols-2 xl:col-span-2">
          <section className="rounded-lg border border-zinc-200 bg-white p-5 shadow-sm">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-sm font-semibold text-zinc-500">
                  등록 내용
                </p>
                <h2 className="mt-1 text-xl font-bold tracking-normal">
                  {selectedDomain?.label ?? "감시 영역"}
                </h2>
              </div>
              <span className="rounded-md bg-zinc-100 px-2 py-1 text-xs font-semibold text-zinc-700">
                {selectedCadence.label}
              </span>
            </div>

            <dl className="mt-5 grid gap-3 text-sm">
              <div className="flex items-start justify-between gap-4">
                <dt className="text-zinc-500">알림</dt>
                <dd className="font-semibold text-zinc-900">
                  {selectedChannel.label}
                </dd>
              </div>
              <div className="flex items-start justify-between gap-4">
                <dt className="text-zinc-500">수신</dt>
                <dd className="break-all font-semibold text-zinc-900">
                  {form.notificationTargetAddress || "-"}
                </dd>
              </div>
              <div className="flex items-start justify-between gap-4">
                <dt className="text-zinc-500">반복 설정</dt>
                <dd className="font-mono text-xs font-semibold text-zinc-900">
                  {previewPayload?.cronExpr ?? "-"}
                </dd>
              </div>
              <div className="grid gap-1">
                <dt className="text-zinc-500">요청</dt>
                <dd className="leading-6 text-zinc-900">{form.query}</dd>
              </div>
            </dl>
          </section>

          <section className="rounded-lg border border-zinc-200 bg-white p-5 shadow-sm">
            <p className="text-sm font-semibold text-zinc-500">등록 결과</p>
            {result ? (
              result.ok ? (
                <SuccessResult result={result} />
              ) : (
                <div
                  role="alert"
                  className="mt-4 rounded-lg border border-red-200 bg-red-50 p-4"
                >
                  <p className="text-sm font-bold text-red-900">
                    {result.error.code}
                  </p>
                  <p className="mt-2 text-sm leading-6 text-red-800">
                    {result.error.message}
                  </p>
                </div>
              )
            ) : (
              <div className="mt-4 rounded-lg border border-dashed border-zinc-300 p-4 text-sm leading-6 text-zinc-500">
                등록 후 상태가 여기에 표시됩니다.
              </div>
            )}
          </section>
        </aside>
      </div>
    </section>
  );
}

function SuccessResult({
  result,
}: {
  result: Extract<CreateSubscriptionResult, { ok: true }>;
}) {
  return (
    <div className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 p-4">
      <p className="text-sm font-bold text-emerald-950">
        알림이 등록되었습니다.
      </p>
      <dl className="mt-4 grid gap-3 text-sm">
        <ResultRow label="subscription" value={result.data.id} />
        <ResultRow label="schedule" value={result.data.scheduleId} />
        <ResultRow label="active" value={result.data.active ? "true" : "false"} />
        <ResultRow label="nextRun" value={formatDateTime(result.data.nextRun)} />
      </dl>
    </div>
  );
}

function ResultRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-1">
      <dt className="text-xs font-semibold text-emerald-800">{label}</dt>
      <dd className="break-all font-mono text-xs text-emerald-950">{value}</dd>
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

function classNames(...classes: Array<string | false | null | undefined>) {
  return classes.filter(Boolean).join(" ");
}
