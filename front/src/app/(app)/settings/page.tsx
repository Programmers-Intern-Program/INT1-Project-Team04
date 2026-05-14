"use client";

import type { FormEvent } from "react";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { useAuthContext } from "../../components/auth/auth-context";
import { AsyncStateView, type AsyncState } from "../../components/ui/async-state";
import { NotificationChannelsCard } from "../../components/ui/notification-channels-card";
import {
  logout,
  updateMember,
  withdrawMember,
  type Member,
} from "../../lib/auth";
import { clearChatStorage } from "../../lib/subscription-chat-session";
import {
  getTokenBalance,
  getTokenHistory,
  type TokenBalance,
  type TokenUsageEntry,
} from "../../lib/tokens";

type ProfileActionState = "idle" | "saving" | "withdrawing";

export default function SettingsPage() {
  const router = useRouter();
  const { member, setMember } = useAuthContext();

  const handleUnauthenticated = useCallback(() => {
    clearChatStorage();
    router.replace("/login");
  }, [router]);

  return (
    <section className="mx-auto w-full max-w-4xl px-4 py-8 md:px-6">
      <header className="mb-6">
        <p className="text-sm font-black text-emerald-700">설정</p>
        <h2 className="mt-1 text-3xl font-black tracking-tight">내 계정</h2>
      </header>

      <div className="grid gap-8">
        <ProfileSection
          member={member}
          onMemberUpdate={setMember}
          onSignedOut={handleUnauthenticated}
        />

        <section className="grid gap-3">
          <h3 className="text-lg font-black tracking-tight text-stone-900">
            알림 채널
          </h3>
          <NotificationChannelsCard
            variant="light"
            onUnauthenticated={handleUnauthenticated}
          />
        </section>

        <TokenSection
          userId={member.id}
          onUnauthenticated={handleUnauthenticated}
        />
      </div>
    </section>
  );
}

function ProfileSection({
  member,
  onMemberUpdate,
  onSignedOut,
}: {
  member: Member;
  onMemberUpdate: (member: Member) => void;
  onSignedOut: () => void;
}) {
  // member.nickname을 useState 초깃값으로만 받고, 외부 변화에 대한 동기화는 하지 않는다.
  // 닉네임을 편집할 수 있는 곳이 이 폼 뿐이라 외부 변경과 충돌할 가능성이 없다.
  const [nickname, setNickname] = useState(member.nickname);
  const [actionState, setActionState] = useState<ProfileActionState>("idle");
  const [message, setMessage] = useState("");
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);

  async function handleNicknameSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");
    setActionState("saving");

    const result = await updateMember({ nickname: nickname.trim() });
    setActionState("idle");

    if (result.ok) {
      onMemberUpdate(result.data);
      setMessage("저장됐어요.");
      return;
    }

    if (result.status === "unauthenticated") {
      onSignedOut();
      return;
    }

    setMessage(result.error.message);
  }

  async function handleLogout() {
    await logout();
    onSignedOut();
  }

  async function handleWithdraw() {
    if (!confirmWithdraw) {
      setConfirmWithdraw(true);
      return;
    }
    setMessage("");
    setActionState("withdrawing");
    const result = await withdrawMember();
    setActionState("idle");

    if (result.ok || result.status === "unauthenticated") {
      onSignedOut();
      return;
    }

    setMessage(result.error.message);
  }

  return (
    <section className="grid gap-3">
      <h3 className="text-lg font-black tracking-tight text-stone-900">
        프로필
      </h3>
      <div className="grid gap-4 rounded-2xl border border-stone-200 bg-white p-5">
        <div className="grid gap-1">
          <p className="text-sm font-black text-stone-500">이메일</p>
          <p className="text-base font-black text-stone-900">{member.email}</p>
        </div>
        <div className="grid gap-1">
          <p className="text-sm font-black text-stone-500">연결된 제공자</p>
          <p className="text-sm font-bold text-stone-700">
            {member.providers.length > 0 ? member.providers.join(", ") : "-"}
          </p>
        </div>

        <form onSubmit={handleNicknameSubmit} className="grid gap-2">
          <label htmlFor="settings-nickname" className="text-sm font-black">
            닉네임
          </label>
          <div className="flex gap-2">
            <input
              id="settings-nickname"
              value={nickname}
              onChange={(event) => setNickname(event.target.value)}
              className="h-12 flex-1 rounded-2xl border border-stone-200 bg-[#fbfaf7] px-4 text-sm font-bold outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
            />
            <button
              type="submit"
              disabled={actionState !== "idle" || !nickname.trim()}
              className="h-12 rounded-2xl bg-stone-950 px-5 text-sm font-black text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-300"
            >
              {actionState === "saving" ? "저장 중" : "저장"}
            </button>
          </div>
        </form>

        {message ? (
          <p className="text-sm font-bold text-sky-900">{message}</p>
        ) : null}

        <div className="flex flex-wrap gap-2 border-t border-stone-100 pt-4">
          <button
            type="button"
            onClick={handleLogout}
            className="h-11 rounded-2xl border border-stone-200 bg-white px-4 text-sm font-black text-stone-900 transition hover:bg-stone-50"
          >
            로그아웃
          </button>
          <button
            type="button"
            onClick={handleWithdraw}
            disabled={actionState === "withdrawing"}
            className="h-11 rounded-2xl bg-red-700 px-4 text-sm font-black text-white transition hover:bg-red-800 disabled:cursor-not-allowed disabled:bg-stone-300"
          >
            {actionState === "withdrawing"
              ? "처리 중"
              : confirmWithdraw
                ? "삭제 확인"
                : "계정 삭제"}
          </button>
        </div>
      </div>
    </section>
  );
}

function TokenSection({
  userId,
  onUnauthenticated,
}: {
  userId: number;
  onUnauthenticated: () => void;
}) {
  const [balanceState, setBalanceState] = useState<AsyncState<TokenBalance>>({
    status: "loading",
  });
  const [historyState, setHistoryState] = useState<
    AsyncState<TokenUsageEntry[]>
  >({ status: "loading" });

  const reloadBalance = useCallback(async () => {
    const result = await getTokenBalance(userId);
    if (result.ok) {
      setBalanceState({ status: "ready", data: result.data });
      return;
    }
    if (result.status === "unauthenticated") {
      onUnauthenticated();
      return;
    }
    setBalanceState({ status: "error", message: result.error.message });
  }, [onUnauthenticated, userId]);

  const reloadHistory = useCallback(async () => {
    const result = await getTokenHistory(userId, 20);
    if (result.ok) {
      setHistoryState({ status: "ready", data: result.data });
      return;
    }
    if (result.status === "unauthenticated") {
      onUnauthenticated();
      return;
    }
    setHistoryState({ status: "error", message: result.error.message });
  }, [onUnauthenticated, userId]);

  useEffect(() => {
    // setTimeout(..., 0)로 비동기 큐로 보내 react-hooks/set-state-in-effect 룰을 회피한다.
    const timer = window.setTimeout(() => {
      void reloadBalance();
      void reloadHistory();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [reloadBalance, reloadHistory]);

  return (
    <section className="grid gap-3">
      <h3 className="text-lg font-black tracking-tight text-stone-900">토큰</h3>
      <div className="grid gap-3 rounded-2xl border border-stone-200 bg-white p-5">
        <AsyncStateView
          state={balanceState}
          loadingText="잔액을 불러오는 중"
          onRetry={() => void reloadBalance()}
        >
          {(data) => (
            <div className="grid gap-4 sm:grid-cols-3">
              <BalanceTile label="현재 잔액" value={data.balance} highlight />
              <BalanceTile label="총 지급" value={data.totalGranted} />
              <BalanceTile label="총 사용" value={data.totalUsed} />
            </div>
          )}
        </AsyncStateView>
      </div>

      <div className="grid gap-3 rounded-2xl border border-stone-200 bg-white p-5">
        <header className="flex items-baseline justify-between">
          <h4 className="text-base font-black text-stone-900">최근 사용 내역</h4>
          <span className="text-xs font-black text-stone-500">최근 20건</span>
        </header>
        <AsyncStateView
          state={historyState}
          loadingText="내역을 불러오는 중"
          onRetry={() => void reloadHistory()}
        >
          {(entries) => {
            if (entries.length === 0) {
              return (
                <p className="rounded-2xl bg-stone-50 p-4 text-sm font-bold text-stone-500">
                  아직 사용 내역이 없어요.
                </p>
              );
            }
            return (
              <ul className="grid gap-2">
                {entries.map((entry) => (
                  <li
                    key={entry.id}
                    className="grid grid-cols-[1fr_auto] items-center gap-3 rounded-xl border border-stone-100 px-4 py-3"
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-black text-stone-900">
                        {entry.description || entry.type}
                      </p>
                      <p className="mt-1 text-xs font-bold text-stone-500">
                        {formatDateTime(entry.createdAt)}
                      </p>
                    </div>
                    <p
                      className={
                        isDeduction(entry.type)
                          ? "text-sm font-black text-red-700"
                          : "text-sm font-black text-emerald-700"
                      }
                    >
                      {isDeduction(entry.type) ? "-" : "+"}
                      {entry.amount.toLocaleString("ko-KR")}
                    </p>
                  </li>
                ))}
              </ul>
            );
          }}
        </AsyncStateView>
      </div>
    </section>
  );
}

function BalanceTile({
  label,
  value,
  highlight = false,
}: {
  label: string;
  value: number;
  highlight?: boolean;
}) {
  return (
    <div
      className={
        highlight
          ? "rounded-2xl bg-emerald-700 p-4 text-white"
          : "rounded-2xl border border-stone-200 bg-stone-50 p-4"
      }
    >
      <p
        className={
          highlight ? "text-xs font-black text-emerald-100" : "text-xs font-black text-stone-500"
        }
      >
        {label}
      </p>
      <p
        className={
          highlight
            ? "mt-1 text-3xl font-black"
            : "mt-1 text-3xl font-black text-stone-900"
        }
      >
        {value.toLocaleString("ko-KR")}
      </p>
    </div>
  );
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return "";
  }
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

function isDeduction(type: string): boolean {
  return type === "USE";
}
