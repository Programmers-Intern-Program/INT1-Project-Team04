"use client";

import type { FormEvent } from "react";
import { useMemo, useState } from "react";

import {
  buildSubscriptionPayload,
  CADENCE_PRESETS,
  CHANNEL_PRESETS,
  createSubscription,
  DOMAIN_PRESETS,
  validateSubscriptionForm,
  type CreateSubscriptionResult,
  type SubscriptionFormState,
} from "../lib/subscriptions";

type ChannelId = (typeof CHANNEL_PRESETS)[number]["id"];
type SubmitState = "idle" | "submitting";

const DEFAULT_FORM: SubscriptionFormState = {
  query: "강남 투룸 전세 시세 바뀌면 알려줘",
  userId: "1",
  domainId: "1",
  cadenceId: "hourly",
};

export function SubscriptionMvp() {
  const [form, setForm] = useState<SubscriptionFormState>(DEFAULT_FORM);
  const [channelId, setChannelId] = useState<ChannelId>("discord");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [result, setResult] = useState<CreateSubscriptionResult | null>(null);
  const [submitState, setSubmitState] = useState<SubmitState>("idle");

  const selectedDomain = useMemo(
    () =>
      DOMAIN_PRESETS.find((domain) => domain.id === Number(form.domainId)) ??
      DOMAIN_PRESETS[0],
    [form.domainId],
  );
  const selectedCadence = useMemo(
    () =>
      CADENCE_PRESETS.find((cadence) => cadence.id === form.cadenceId) ??
      CADENCE_PRESETS[0],
    [form.cadenceId],
  );
  const selectedChannel = useMemo(
    () =>
      CHANNEL_PRESETS.find((channel) => channel.id === channelId) ??
      CHANNEL_PRESETS[0],
    [channelId],
  );
  const previewPayload = useMemo(() => buildSubscriptionPayload(form), [form]);

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
    setResult(response);
    setSubmitState("idle");
  }

  return (
    <main className="min-h-screen bg-[#f7f7f4] text-zinc-950">
      <div className="mx-auto grid min-h-screen w-full max-w-7xl gap-5 px-4 py-5 md:px-6 lg:grid-cols-[300px_minmax(0,1fr)_360px]">
        <aside className="flex flex-col gap-4 rounded-lg border border-zinc-200 bg-white p-5 shadow-sm">
          <div className="space-y-3">
            <p className="text-sm font-semibold text-emerald-700">
              AI monitoring agent
            </p>
            <div>
              <h1 className="text-3xl font-bold tracking-normal text-zinc-950">
                지켜봐줄게
              </h1>
              <p className="mt-3 text-sm leading-6 text-zinc-600">
                부동산, 법률, 채용, 경매 변화를 놓치지 않도록 필요한
                신호만 등록합니다.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2 lg:grid-cols-1">
            {DOMAIN_PRESETS.map((domain) => {
              const isActive = selectedDomain.id === domain.id;

              return (
                <button
                  key={domain.id}
                  type="button"
                  aria-pressed={isActive}
                  onClick={() => {
                    setForm((current) => ({
                      ...current,
                      domainId: String(domain.id),
                      query: current.query.trim() ? current.query : domain.example,
                    }));
                    setErrors((current) => ({ ...current, domainId: "" }));
                  }}
                  className={classNames(
                    "min-h-28 rounded-lg border p-4 text-left transition",
                    isActive
                      ? "border-emerald-700 bg-emerald-50 text-emerald-950"
                      : "border-zinc-200 bg-zinc-50 text-zinc-800 hover:border-zinc-300 hover:bg-white",
                  )}
                >
                  <span className="block text-sm font-semibold">
                    {domain.label}
                  </span>
                  <span className="mt-2 block text-xs leading-5 text-zinc-600">
                    {domain.description}
                  </span>
                </button>
              );
            })}
          </div>
        </aside>

        <section className="rounded-lg border border-zinc-200 bg-white p-5 shadow-sm md:p-6">
          <form onSubmit={handleSubmit} className="flex h-full flex-col gap-6">
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-zinc-500">
                    자연어 태스크 등록
                  </p>
                  <h2 className="mt-1 text-2xl font-bold tracking-normal text-zinc-950">
                    어떤 변화를 지켜볼까요?
                  </h2>
                </div>
                <span className="rounded-md border border-amber-200 bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-800">
                  MVP
                </span>
              </div>

              <label
                htmlFor="query"
                className="text-sm font-semibold text-zinc-800"
              >
                감시 요청
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
                        <span
                          className={classNames(
                            "mt-1 block text-xs leading-5",
                            isActive ? "text-zinc-300" : "text-zinc-500",
                          )}
                        >
                          {cadence.description}
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
                    const isActive = channel.id === channelId;

                    return (
                      <button
                        key={channel.id}
                        type="button"
                        aria-pressed={isActive}
                        onClick={() => setChannelId(channel.id)}
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
                        <span className="mt-1 block text-xs leading-5 text-zinc-500">
                          {channel.description}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </fieldset>
            </div>

            <div className="grid gap-4 border-t border-zinc-200 pt-5 sm:grid-cols-2">
              <div className="space-y-2">
                <label
                  htmlFor="userId"
                  className="text-sm font-semibold text-zinc-800"
                >
                  사용자 ID
                </label>
                <input
                  id="userId"
                  type="number"
                  min="1"
                  value={form.userId}
                  onChange={(event) => {
                    setForm((current) => ({
                      ...current,
                      userId: event.target.value,
                    }));
                    setErrors((current) => ({ ...current, userId: "" }));
                  }}
                  className="h-11 w-full rounded-lg border border-zinc-300 bg-zinc-50 px-3 outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
                />
                {errors.userId ? (
                  <p role="alert" className="text-sm font-medium text-red-700">
                    {errors.userId}
                  </p>
                ) : null}
              </div>

              <div className="space-y-2">
                <label
                  htmlFor="domainId"
                  className="text-sm font-semibold text-zinc-800"
                >
                  도메인 ID
                </label>
                <input
                  id="domainId"
                  type="number"
                  min="1"
                  value={form.domainId}
                  onChange={(event) => {
                    setForm((current) => ({
                      ...current,
                      domainId: event.target.value,
                    }));
                    setErrors((current) => ({ ...current, domainId: "" }));
                  }}
                  className="h-11 w-full rounded-lg border border-zinc-300 bg-zinc-50 px-3 outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
                />
                {errors.domainId ? (
                  <p role="alert" className="text-sm font-medium text-red-700">
                    {errors.domainId}
                  </p>
                ) : null}
              </div>
            </div>

            <button
              type="submit"
              disabled={submitState === "submitting"}
              className="mt-auto h-12 rounded-lg bg-emerald-700 px-5 text-sm font-bold text-white transition hover:bg-emerald-800 disabled:cursor-not-allowed disabled:bg-zinc-400"
            >
              {submitState === "submitting" ? "등록 중..." : "감시 태스크 등록"}
            </button>
          </form>
        </section>

        <aside className="flex flex-col gap-4">
          <section className="rounded-lg border border-zinc-200 bg-white p-5 shadow-sm">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-sm font-semibold text-zinc-500">
                  등록 미리보기
                </p>
                <h2 className="mt-1 text-xl font-bold tracking-normal">
                  {selectedDomain.label}
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
                <dt className="text-zinc-500">cron</dt>
                <dd className="font-mono text-xs font-semibold text-zinc-900">
                  {previewPayload.cronExpr}
                </dd>
              </div>
            </dl>

            <pre className="mt-5 max-h-72 overflow-auto rounded-lg bg-zinc-950 p-4 text-xs leading-5 text-zinc-100">
              {JSON.stringify(previewPayload, null, 2)}
            </pre>
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
                백엔드 응답이 여기에 표시됩니다.
              </div>
            )}
          </section>
        </aside>
      </div>
    </main>
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
        감시 태스크가 등록되었습니다.
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
