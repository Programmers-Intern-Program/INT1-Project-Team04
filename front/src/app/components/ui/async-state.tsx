"use client";

import type { ReactNode } from "react";

export type AsyncState<T> =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; data: T };

export function AsyncStateView<T>({
  state,
  loadingText = "불러오는 중",
  onRetry,
  children,
}: {
  state: AsyncState<T>;
  loadingText?: string;
  onRetry?: () => void;
  children: (data: T) => ReactNode;
}): ReactNode {
  if (state.status === "loading") {
    return (
      <p className="text-sm font-bold text-stone-500">{loadingText}</p>
    );
  }

  if (state.status === "error") {
    return (
      <div className="grid gap-2 rounded-2xl border border-red-100 bg-red-50 p-4 text-sm font-bold text-red-900">
        <p>{state.message}</p>
        {onRetry ? (
          <button
            type="button"
            onClick={onRetry}
            className="w-fit rounded-full border border-red-300 bg-white px-3 py-1 text-xs font-black text-red-700"
          >
            다시 시도
          </button>
        ) : null}
      </div>
    );
  }

  return children(state.data);
}
