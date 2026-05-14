"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import {
  buildOAuthLoginUrl,
  getCurrentMember,
  type OAuthProvider,
} from "../../lib/auth";

type LoginState = "checking" | "ready" | "error";

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

export default function LoginPage() {
  const router = useRouter();
  const [state, setState] = useState<LoginState>("checking");
  const [errorMessage, setErrorMessage] = useState("");

  useEffect(() => {
    let active = true;

    async function check() {
      const result = await getCurrentMember();
      if (!active) {
        return;
      }

      if (result.ok) {
        router.replace("/");
        return;
      }

      if (result.status === "unauthenticated") {
        setState("ready");
        return;
      }

      setErrorMessage(result.error.message);
      setState("error");
    }

    void check();

    return () => {
      active = false;
    };
  }, [router]);

  if (state === "checking") {
    return (
      <main className="min-h-screen bg-[#f4f1e8] px-4 py-8 text-stone-950">
        <div className="mx-auto flex min-h-[60vh] max-w-md items-center justify-center">
          <p className="text-sm font-black text-stone-500">확인 중</p>
        </div>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-[#f4f1e8] px-4 py-8 text-stone-950">
      <section className="mx-auto flex min-h-[70vh] max-w-md flex-col justify-center gap-8">
        <div>
          <p className="text-sm font-black text-emerald-700">관심사 알림</p>
          <h1 className="mt-2 text-5xl font-black tracking-tight">지켜봐줄게</h1>
        </div>

        {state === "error" ? (
          <p className="text-sm font-bold leading-6 text-red-700">
            {errorMessage}
          </p>
        ) : null}

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
