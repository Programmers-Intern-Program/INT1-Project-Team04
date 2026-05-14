"use client";

import type { ReactNode } from "react";

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="grid place-items-center gap-3 rounded-2xl border border-stone-200 bg-white p-8 text-center">
      <p className="text-base font-black text-stone-900">{title}</p>
      {description ? (
        <p className="max-w-sm text-sm font-bold leading-6 text-stone-500">
          {description}
        </p>
      ) : null}
      {action}
    </div>
  );
}
