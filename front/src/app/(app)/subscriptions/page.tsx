"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

import { EmptyState } from "../../components/ui/empty-state";
import { SubscriptionCard } from "../../components/ui/subscription-card";
import { AsyncStateView, type AsyncState } from "../../components/ui/async-state";
import {
  deleteSubscriptionSummary,
  getSubscriptionSummaries,
  type SubscriptionSummary,
} from "../../lib/subscription-conversations";
import {
  groupSubscriptionsByDomain,
  type SubscriptionGroup,
} from "../../lib/subscription-grouping";

export default function SubscriptionsPage() {
  const router = useRouter();
  const [state, setState] = useState<AsyncState<SubscriptionSummary[]>>({
    status: "loading",
  });
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState("");

  const handleUnauthenticated = useCallback(() => {
    router.replace("/login");
  }, [router]);

  const reload = useCallback(async () => {
    const response = await getSubscriptionSummaries();
    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        handleUnauthenticated();
        return;
      }
      setState({ status: "error", message: response.error.message });
      return;
    }
    setState({ status: "ready", data: response.data });
  }, [handleUnauthenticated]);

  useEffect(() => {
    // setTimeout(..., 0)로 비동기 큐로 보내 react-hooks/set-state-in-effect 룰을 회피한다.
    const timer = window.setTimeout(() => {
      void reload();
    }, 0);
    window.addEventListener("focus", reload);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("focus", reload);
    };
  }, [reload]);

  async function handleDelete(subscriptionId: string) {
    if (deletingId) {
      return;
    }
    setStatusMessage("");
    setDeletingId(subscriptionId);

    const before = state.status === "ready" ? state.data : [];
    // 낙관적 업데이트: UI 먼저 제거
    if (state.status === "ready") {
      setState({
        status: "ready",
        data: before.filter((item) => item.id !== subscriptionId),
      });
    }

    const response = await deleteSubscriptionSummary(subscriptionId);
    setDeletingId(null);

    if (!response.ok) {
      if (response.error.code === "UNAUTHENTICATED") {
        handleUnauthenticated();
        return;
      }
      // 롤백
      setState({ status: "ready", data: before });
      setStatusMessage(response.error.message);
      return;
    }

    setStatusMessage("알림 구독을 삭제했어요.");
    // 백그라운드 재페치로 백엔드와 동기화
    void reload();
  }

  return (
    <section className="mx-auto w-full max-w-5xl px-4 py-8 md:px-6">
      <header className="mb-6 flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-black text-emerald-700">구독 목록</p>
          <h2 className="mt-1 text-3xl font-black tracking-tight">
            내가 지켜보는 알림
          </h2>
        </div>
        <Link
          href="/"
          className="rounded-full bg-stone-950 px-5 py-2 text-sm font-black text-white transition hover:bg-stone-800"
        >
          알림 만들기
        </Link>
      </header>

      {statusMessage ? (
        <p className="mb-4 text-sm font-bold text-sky-900">{statusMessage}</p>
      ) : null}

      <AsyncStateView state={state} loadingText="구독을 불러오는 중" onRetry={() => void reload()}>
        {(items) => {
          if (items.length === 0) {
            return (
              <EmptyState
                title="아직 시작한 알림이 없어요"
                description="자연어로 어떤 변화를 지켜볼지 알려주시면 바로 시작할 수 있어요."
                action={
                  <Link
                    href="/"
                    className="mt-2 inline-flex h-12 items-center rounded-2xl bg-stone-950 px-5 text-sm font-black text-white transition hover:bg-stone-800"
                  >
                    알림 만들기
                  </Link>
                }
              />
            );
          }

          const groups: SubscriptionGroup[] = groupSubscriptionsByDomain(items);

          return (
            <div className="grid gap-8">
              {groups.map((group) => (
                <section key={group.label} className="grid gap-3">
                  <header className="flex items-baseline justify-between">
                    <h3 className="text-lg font-black tracking-tight text-stone-900">
                      {group.label}
                    </h3>
                    <span className="text-xs font-black text-stone-500">
                      {group.items.length}건
                    </span>
                  </header>
                  <div className="grid gap-3 md:grid-cols-2">
                    {group.items.map((subscription) => (
                      <SubscriptionCard
                        key={subscription.id}
                        subscription={subscription}
                        variant="light"
                        deleting={deletingId === subscription.id}
                        disableDelete={deletingId !== null}
                        onDelete={() => void handleDelete(subscription.id)}
                      />
                    ))}
                  </div>
                </section>
              ))}
            </div>
          );
        }}
      </AsyncStateView>
    </section>
  );
}
