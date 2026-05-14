"use client";

import { useRouter } from "next/navigation";

import { SubscriptionChat } from "../components/subscription-chat";

export default function ChatHomePage() {
  const router = useRouter();
  return (
    <section className="mx-auto w-full max-w-7xl px-4 py-6 md:px-6">
      <SubscriptionChat onUnauthenticated={() => router.replace("/login")} />
    </section>
  );
}
