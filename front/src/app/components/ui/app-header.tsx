"use client";

import Link from "next/link";

import type { Member } from "../../lib/auth";
import { NavTabs } from "./nav-tabs";
import { ProfileMenu } from "./profile-menu";

type AppHeaderProps = {
  member: Member;
  onMemberUpdate: (member: Member) => void;
  onSignedOut: () => void;
};

export function AppHeader({
  member,
  onMemberUpdate,
  onSignedOut,
}: AppHeaderProps) {
  return (
    <header className="border-b border-stone-200/80 bg-[#fffaf0]/90 backdrop-blur">
      <div className="mx-auto flex w-full max-w-7xl items-center justify-between gap-4 px-4 py-5 md:px-6">
        <Link href="/" className="block">
          <p className="text-sm font-black text-emerald-700">관심사 알림</p>
          <h1 className="mt-1 text-2xl font-black tracking-tight">지켜봐줄게</h1>
        </Link>

        <div className="flex items-center gap-3">
          <div className="hidden md:block">
            <NavTabs />
          </div>
          <ProfileMenu
            member={member}
            onMemberUpdate={onMemberUpdate}
            onSignedOut={onSignedOut}
          />
        </div>
      </div>
      <div className="border-t border-stone-200/60 bg-white/60 px-4 py-2 md:hidden">
        <NavTabs />
      </div>
    </header>
  );
}
