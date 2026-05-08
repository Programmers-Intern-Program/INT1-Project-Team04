"use client";

import type { FormEvent } from "react";
import { useEffect, useState } from "react";

import { SubscriptionChat } from "./components/subscription-chat";
import {
  buildOAuthLoginUrl,
  getCurrentMember,
  logout,
  updateMember,
  withdrawMember,
  type Member,
  type OAuthProvider,
} from "./lib/auth";

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
    className: "border-[#f6d90f] bg-[#fee500] text-stone-950 hover:bg-[#f3dc22]",
  },
  {
    id: "google",
    label: "Google",
    className: "border-stone-300 bg-white text-stone-950 hover:bg-stone-50",
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
  const [profileOpen, setProfileOpen] = useState(false);
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
      setMessage("저장됐습니다.");
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
    setProfileOpen(false);
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
      setProfileOpen(false);
      return;
    }

    setMessage(result.error.message);
  }

  if (authState.status === "loading") {
    return (
      <main className="min-h-screen bg-[#f4f1e8] px-4 py-8 text-stone-950">
        <div className="mx-auto flex min-h-[60vh] max-w-3xl items-center justify-center">
          <p className="text-sm font-black text-stone-500">확인 중</p>
        </div>
      </main>
    );
  }

  if (authState.status === "guest") {
    return (
      <main className="min-h-screen bg-[#f4f1e8] px-4 py-8 text-stone-950">
        <section className="mx-auto flex min-h-[70vh] max-w-md flex-col justify-center gap-8">
          <div>
            <p className="text-sm font-black text-emerald-700">관심사 알림</p>
            <h1 className="mt-2 text-5xl font-black tracking-tight">
              지켜봐줄게
            </h1>
          </div>

          <div className="grid gap-3">
            {LOGIN_PROVIDERS.map((provider) => (
              <a
                key={provider.id}
                href={buildOAuthLoginUrl(provider.id)}
                className={`flex h-13 items-center justify-center rounded-2xl border px-4 text-sm font-black transition ${provider.className}`}
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
      <main className="min-h-screen bg-[#f4f1e8] px-4 py-8 text-stone-950">
        <section className="mx-auto flex min-h-[60vh] max-w-xl flex-col justify-center gap-4">
          <h1 className="text-2xl font-black tracking-tight">
            상태를 불러오지 못했습니다.
          </h1>
          <p className="text-sm font-bold leading-6 text-red-700">
            {authState.message}
          </p>
          <button
            type="button"
            onClick={() => setAuthState({ status: "guest" })}
            className="h-12 rounded-2xl border border-stone-300 bg-white px-4 text-sm font-black text-stone-900"
          >
            로그인
          </button>
        </section>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-[#f4f1e8] text-stone-950">
      <header className="border-b border-stone-200/80 bg-[#fffaf0]/90 backdrop-blur">
        <div className="mx-auto flex w-full max-w-7xl items-center justify-between gap-4 px-4 py-5 md:px-6">
          <div>
            <p className="text-sm font-black text-emerald-700">관심사 알림</p>
            <h1 className="mt-1 text-2xl font-black tracking-tight">
              지켜봐줄게
            </h1>
          </div>

          <div className="relative">
            <button
              type="button"
              onClick={() => {
                setProfileOpen((current) => !current);
                setConfirmWithdraw(false);
                setMessage("");
              }}
              className="flex h-12 items-center gap-3 rounded-full border border-stone-200 bg-white px-3 pr-5 text-sm font-black text-stone-950 shadow-sm transition hover:border-stone-400"
              aria-expanded={profileOpen}
            >
              <span className="grid size-8 place-items-center rounded-full bg-emerald-700 text-white">
                {getInitial(authState.member.nickname)}
              </span>
              <span>{authState.member.nickname}</span>
            </button>

            {profileOpen ? (
              <div className="absolute right-0 z-20 mt-3 w-[min(360px,calc(100vw-2rem))] rounded-[28px] border border-stone-200 bg-white p-4 shadow-[0_24px_80px_rgba(28,25,23,0.18)]">
                <div className="mb-4 flex items-start justify-between gap-4">
                  <div>
                    <h2 className="text-lg font-black tracking-tight">프로필</h2>
                    <p className="mt-1 text-sm font-bold text-stone-500">
                      {maskEmail(authState.member.email)}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setProfileOpen(false)}
                    className="grid size-9 place-items-center rounded-full bg-stone-100 text-sm font-black text-stone-700"
                    aria-label="닫기"
                  >
                    ×
                  </button>
                </div>

                <form onSubmit={handleNicknameSubmit} className="grid gap-2">
                  <label htmlFor="nickname" className="text-sm font-black">
                    닉네임
                  </label>
                  <input
                    id="nickname"
                    value={nickname}
                    onChange={(event) => setNickname(event.target.value)}
                    className="h-12 rounded-2xl border border-stone-200 bg-[#fbfaf7] px-4 text-sm font-bold outline-none transition focus:border-emerald-700 focus:bg-white focus:ring-4 focus:ring-emerald-100"
                  />
                  <button
                    type="submit"
                    disabled={actionState !== "idle" || !nickname.trim()}
                    className="h-12 rounded-2xl bg-stone-950 px-4 text-sm font-black text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-300"
                  >
                    {actionState === "saving" ? "저장 중" : "저장"}
                  </button>
                </form>

                {message ? (
                  <p className="mt-3 text-sm font-bold text-stone-600">
                    {message}
                  </p>
                ) : null}

                <div className="mt-4 grid grid-cols-2 gap-2">
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
            ) : null}
          </div>
        </div>
      </header>

      <section className="mx-auto w-full max-w-7xl px-4 py-6 md:px-6">
        <SubscriptionChat
          onUnauthenticated={() => setAuthState({ status: "guest" })}
        />
      </section>
    </main>
  );
}

function getInitial(nickname: string): string {
  return nickname.trim().at(0) ?? "나";
}

function maskEmail(email: string): string {
  const [localPart, domain] = email.split("@");
  if (!localPart || !domain) {
    return "연결됨";
  }

  return `${localPart.at(0) ?? ""}***@${domain}`;
}
