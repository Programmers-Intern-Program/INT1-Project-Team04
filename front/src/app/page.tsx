"use client";

import type { FormEvent } from "react";
import { useEffect, useState } from "react";

import {
  buildOAuthLoginUrl,
  getCurrentMember,
  logout,
  updateMember,
  withdrawMember,
  type Member,
  type OAuthProvider,
} from "./lib/auth";
import { SubscriptionMvp } from "./components/subscription-mvp";

type AuthState =
  | { status: "loading" }
  | { status: "guest" }
  | { status: "member"; member: Member }
  | { status: "error"; message: string };

type ActionState = "idle" | "saving" | "withdrawing";

const LOGIN_PROVIDERS: Array<{
  id: OAuthProvider;
  label: string;
  className: string;
}> = [
  {
    id: "kakao",
    label: "카카오",
    className: "border-[#f6d90f] bg-[#fee500] text-zinc-950 hover:bg-[#f3dc22]",
  },
  {
    id: "google",
    label: "Google",
    className: "border-zinc-300 bg-white text-zinc-950 hover:bg-zinc-50",
  },
  {
    id: "discord",
    label: "Discord",
    className: "border-[#5865f2] bg-[#5865f2] text-white hover:bg-[#4752c4]",
  },
];

export default function Home() {
  const [authState, setAuthState] = useState<AuthState>({ status: "loading" });
  const [nickname, setNickname] = useState("");
  const [actionState, setActionState] = useState<ActionState>("idle");
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    let active = true;

    async function loadMember() {
      const result = await getCurrentMember();
      if (!active) {
        return;
      }

      if (result.ok) {
        setAuthState({ status: "member", member: result.data });
        setNickname(result.data.nickname);
        return;
      }

      if (result.status === "unauthenticated") {
        setAuthState({ status: "guest" });
        return;
      }

      setAuthState({ status: "error", message: result.error.message });
    }

    loadMember();

    return () => {
      active = false;
    };
  }, []);

  async function handleNicknameSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");
    setActionState("saving");

    const result = await updateMember({ nickname: nickname.trim() });
    setActionState("idle");

    if (result.ok) {
      setAuthState({ status: "member", member: result.data });
      setNickname(result.data.nickname);
      setMessage("닉네임이 저장되었습니다.");
      return;
    }

    if (result.status === "unauthenticated") {
      setAuthState({ status: "guest" });
      return;
    }

    setMessage(result.error.message);
  }

  async function handleLogout() {
    await logout();
    setAuthState({ status: "guest" });
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
      setAuthState({ status: "guest" });
      setConfirmWithdraw(false);
      return;
    }

    setMessage(result.error.message);
  }

  if (authState.status === "loading") {
    return (
      <main className="min-h-screen bg-[#f7f7f4] px-4 py-8 text-zinc-950">
        <div className="mx-auto flex min-h-[60vh] max-w-3xl items-center justify-center">
          <p className="text-sm font-semibold text-zinc-500">확인 중...</p>
        </div>
      </main>
    );
  }

  if (authState.status === "guest") {
    return (
      <main className="min-h-screen bg-[#f7f7f4] px-4 py-8 text-zinc-950">
        <section className="mx-auto flex min-h-[70vh] max-w-xl flex-col justify-center gap-8">
          <div className="space-y-3">
            <p className="text-sm font-semibold text-emerald-700">
              관심사 알림
            </p>
            <h1 className="text-4xl font-bold tracking-normal">지켜봐줄게</h1>
            <p className="text-base leading-7 text-zinc-600">
              카카오, Google, Discord 계정으로 바로 시작할 수 있습니다.
            </p>
          </div>

          <div className="grid gap-3">
            {LOGIN_PROVIDERS.map((provider) => (
              <a
                key={provider.id}
                href={buildOAuthLoginUrl(provider.id)}
                className={`flex h-12 items-center justify-center rounded-lg border px-4 text-sm font-bold transition ${provider.className}`}
              >
                {provider.label}
              </a>
            ))}
          </div>
        </section>
      </main>
    );
  }

  if (authState.status === "error") {
    return (
      <main className="min-h-screen bg-[#f7f7f4] px-4 py-8 text-zinc-950">
        <section className="mx-auto flex min-h-[60vh] max-w-xl flex-col justify-center gap-4">
          <h1 className="text-2xl font-bold tracking-normal">
            상태를 불러오지 못했습니다.
          </h1>
          <p className="text-sm leading-6 text-red-700">{authState.message}</p>
          <button
            type="button"
            onClick={() => setAuthState({ status: "guest" })}
            className="h-11 rounded-lg border border-zinc-300 bg-white px-4 text-sm font-bold text-zinc-900"
          >
            로그인 화면으로 이동
          </button>
        </section>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-[#f7f7f4] text-zinc-950">
      <section className="border-b border-zinc-200 bg-white">
        <div className="mx-auto flex w-full max-w-7xl flex-col gap-4 px-4 py-5 md:px-6 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <p className="text-sm font-semibold text-emerald-700">
              관심사 알림
            </p>
            <h1 className="mt-1 text-2xl font-bold tracking-normal">
              지켜봐줄게
            </h1>
          </div>

          <div className="grid gap-3 lg:min-w-[520px] lg:grid-cols-[1fr_auto] lg:items-end">
            <div className="grid gap-2 rounded-lg border border-zinc-200 bg-zinc-50 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-bold">
                  {authState.member.nickname}
                </span>
                <span className="text-xs font-semibold text-zinc-500">
                  {authState.member.email}
                </span>
              </div>
              <div className="flex flex-wrap gap-2">
                {authState.member.providers.map((provider) => (
                  <span
                    key={provider}
                    className="rounded-md bg-white px-2 py-1 text-xs font-bold text-zinc-700"
                  >
                    {provider}
                  </span>
                ))}
              </div>
            </div>

            <button
              type="button"
              onClick={handleLogout}
              className="h-11 rounded-lg border border-zinc-300 bg-white px-4 text-sm font-bold text-zinc-900 transition hover:bg-zinc-50"
            >
              로그아웃
            </button>
          </div>
        </div>
      </section>

      <section className="mx-auto grid w-full max-w-7xl gap-4 px-4 py-5 md:px-6 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div className="min-w-0">
          <SubscriptionMvp
            onUnauthenticated={() => setAuthState({ status: "guest" })}
          />
        </div>

        <aside className="grid content-start gap-4">
          <section className="rounded-lg border border-zinc-200 bg-white p-5 shadow-sm">
            <h2 className="text-lg font-bold tracking-normal">회원정보</h2>
            <form onSubmit={handleNicknameSubmit} className="mt-4 grid gap-3">
              <label
                htmlFor="nickname"
                className="text-sm font-semibold text-zinc-800"
              >
                닉네임
              </label>
              <input
                id="nickname"
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                className="h-11 rounded-lg border border-zinc-300 bg-zinc-50 px-3 text-sm outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
              />
              <button
                type="submit"
                disabled={actionState !== "idle" || !nickname.trim()}
                className="h-11 rounded-lg bg-zinc-950 px-4 text-sm font-bold text-white transition hover:bg-zinc-800 disabled:cursor-not-allowed disabled:bg-zinc-400"
              >
                {actionState === "saving" ? "저장 중..." : "저장"}
              </button>
            </form>
            {message ? (
              <p className="mt-3 text-sm font-semibold text-zinc-600">
                {message}
              </p>
            ) : null}
          </section>

          <section className="rounded-lg border border-red-200 bg-white p-5 shadow-sm">
            <h2 className="text-lg font-bold tracking-normal text-red-900">
              회원 탈퇴
            </h2>
            <button
              type="button"
              onClick={handleWithdraw}
              disabled={actionState === "withdrawing"}
              className="mt-4 h-11 w-full rounded-lg bg-red-700 px-4 text-sm font-bold text-white transition hover:bg-red-800 disabled:cursor-not-allowed disabled:bg-zinc-400"
            >
              {actionState === "withdrawing"
                ? "처리 중..."
                : confirmWithdraw
                  ? "탈퇴 확인"
                  : "탈퇴"}
            </button>
          </section>
        </aside>
      </section>
    </main>
  );
}
