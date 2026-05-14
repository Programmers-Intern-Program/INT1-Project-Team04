"use client";

import type { SubscriptionSummary } from "../../lib/subscription-conversations";

export type SubscriptionCardVariant = "light" | "dark";

type Props = {
  subscription: SubscriptionSummary;
  variant?: SubscriptionCardVariant;
  deleting: boolean;
  disableDelete: boolean;
  onDelete: () => void;
};

export function SubscriptionCard({
  subscription,
  variant = "light",
  deleting,
  disableDelete,
  onDelete,
}: Props) {
  const dark = variant === "dark";

  return (
    <article
      className={
        dark
          ? "rounded-2xl border border-white/10 bg-white/8 p-4"
          : "rounded-2xl border border-stone-200 bg-white p-5"
      }
    >
      <div className="flex items-start justify-between gap-3">
        <h3
          className={
            dark
              ? "min-w-0 text-sm font-black leading-6"
              : "min-w-0 text-base font-black leading-6 text-stone-950"
          }
        >
          {subscription.query}
        </h3>
        <button
          type="button"
          onClick={onDelete}
          disabled={disableDelete}
          aria-label={`${subscription.query} 알림 삭제`}
          className={
            dark
              ? "shrink-0 rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
              : "shrink-0 rounded-full border border-stone-300 bg-white px-3 py-1 text-xs font-black text-stone-700 transition hover:border-red-500 hover:text-red-700 disabled:cursor-not-allowed disabled:text-stone-300"
          }
        >
          {deleting ? "삭제 중" : "삭제"}
        </button>
      </div>
      <dl className="mt-3 grid gap-2 text-sm">
        <SummaryRow label="영역" value={subscription.domainLabel} dark={dark} />
        <SummaryRow label="알림 방식" value={subscription.cadenceLabel} dark={dark} />
        <SummaryRow label="채널" value={subscription.channelLabel || "-"} dark={dark} />
        {subscription.nextRun ? (
          <SummaryRow label="다음 실행" value={formatNextRun(subscription.nextRun)} dark={dark} />
        ) : null}
      </dl>
    </article>
  );
}

function SummaryRow({
  label,
  value,
  dark,
}: {
  label: string;
  value: string;
  dark: boolean;
}) {
  return (
    <div className="grid gap-0.5">
      <dt className={dark ? "text-xs font-black text-emerald-100" : "text-xs font-black text-emerald-800"}>
        {label}
      </dt>
      <dd className={dark ? "break-words font-black text-white" : "break-words font-black text-emerald-950"}>
        {value}
      </dd>
    </div>
  );
}

function formatNextRun(value: string): string {
  // 백엔드가 LocalDateTime ISO 문자열을 보낸다. 사용자가 읽기 쉬운 한국식 형식으로 변환.
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
