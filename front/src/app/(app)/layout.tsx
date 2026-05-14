"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { AuthContext } from "../components/auth/auth-context";
import { AppHeader } from "../components/ui/app-header";
import { getCurrentMember, type Member } from "../lib/auth";

type AuthState =
  | { status: "loading" }
  | { status: "guest" }
  | { status: "member"; member: Member }
  | { status: "error"; message: string };

export default function AppLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const [authState, setAuthState] = useState<AuthState>({ status: "loading" });

  useEffect(() => {
    let active = true;

    async function loadMember() {
      const result = await getCurrentMember();
      if (!active) {
        return;
      }

      if (result.ok) {
        setAuthState({ status: "member", member: result.data });
        return;
      }

      if (result.status === "unauthenticated") {
        setAuthState({ status: "guest" });
        return;
      }

      setAuthState({ status: "error", message: result.error.message });
    }

    void loadMember();

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (authState.status === "guest") {
      router.replace("/login");
    }
  }, [authState.status, router]);

  const setMember = useCallback((member: Member) => {
    setAuthState({ status: "member", member });
  }, []);

  const handleSignedOut = useCallback(() => {
    setAuthState({ status: "guest" });
  }, []);

  if (authState.status === "loading") {
    return (
      <main className="min-h-screen bg-[#f4f1e8] px-4 py-8 text-stone-950">
        <div className="mx-auto flex min-h-[60vh] max-w-3xl items-center justify-center">
          <p className="text-sm font-black text-stone-500">확인 중</p>
        </div>
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
            onClick={() => router.replace("/login")}
            className="h-12 rounded-2xl border border-stone-300 bg-white px-4 text-sm font-black text-stone-900"
          >
            로그인
          </button>
        </section>
      </main>
    );
  }

  if (authState.status === "guest") {
    return null;
  }

  return (
    <AuthContext.Provider
      value={{ member: authState.member, setMember }}
    >
      <div className="flex min-h-screen flex-col bg-[#f4f1e8] text-stone-950">
        <AppHeader
          member={authState.member}
          onMemberUpdate={setMember}
          onSignedOut={handleSignedOut}
        />
        <div className="flex-1">{children}</div>
      </div>
    </AuthContext.Provider>
  );
}
