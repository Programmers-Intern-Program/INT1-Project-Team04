"use client";

export type ChannelEndpointVariant = "light" | "dark";

export function ChannelEndpointRow({
  label,
  status,
  hint,
  actionLabel,
  disabled,
  busy,
  variant = "dark",
  onAction,
}: {
  label: string;
  status: string;
  hint?: string;
  actionLabel: string;
  disabled: boolean;
  busy: boolean;
  variant?: ChannelEndpointVariant;
  onAction: () => void;
}) {
  const dark = variant === "dark";
  return (
    <div
      className={
        dark
          ? "flex items-start justify-between gap-3 rounded-xl bg-white/6 p-3"
          : "flex items-start justify-between gap-3 rounded-xl border border-stone-200 bg-white p-4"
      }
    >
      <div>
        <p className={dark ? "text-sm font-black" : "text-sm font-black text-stone-900"}>
          {label}
        </p>
        <p
          className={
            dark
              ? "mt-1 text-xs font-bold text-emerald-100"
              : "mt-1 text-xs font-bold text-stone-500"
          }
        >
          {status}
        </p>
        {hint ? (
          <p
            className={
              dark
                ? "mt-1 max-w-44 text-xs font-bold leading-5 text-emerald-50/80"
                : "mt-1 max-w-64 text-xs font-bold leading-5 text-stone-500"
            }
          >
            {hint}
          </p>
        ) : null}
      </div>
      <div className="flex shrink-0 flex-col gap-2">
        <button
          type="button"
          onClick={onAction}
          disabled={disabled}
          className={
            dark
              ? "rounded-full border border-white/15 px-3 py-1 text-xs font-black text-emerald-50 transition hover:border-white/40 hover:bg-white/10 disabled:cursor-not-allowed disabled:text-emerald-100/50"
              : "rounded-full border border-stone-300 bg-white px-3 py-1 text-xs font-black text-stone-700 transition hover:border-stone-500 disabled:cursor-not-allowed disabled:text-stone-300"
          }
        >
          {busy ? "처리 중" : actionLabel}
        </button>
      </div>
    </div>
  );
}
