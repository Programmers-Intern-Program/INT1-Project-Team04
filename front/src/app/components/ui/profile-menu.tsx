"use client";

import type { FormEvent } from "react";
import { useState } from "react";

import {
  logout,
  updateMember,
  withdrawMember,
  type Member,
} from "../../lib/auth";

type ActionState = "idle" | "saving" | "withdrawing";

type ProfileMenuProps = {
  member: Member;
  onMemberUpdate: (member: Member) => void;
  onSignedOut: () => void;
};

export function ProfileMenu({
  member,
  onMemberUpdate,
  onSignedOut,
}: ProfileMenuProps) {
  const [open, setOpen] = useState(false);
  const [nickname, setNickname] = useState(member.nickname);
  const [actionState, setActionState] = useState<ActionState>("idle");
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);
  const [message, setMessage] = useState("");

  function toggleOpen() {
    setOpen((current) => !current);
    setConfirmWithdraw(false);
    setMessage("");
    setNickname(member.nickname);
  }

  async function handleNicknameSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");
    setActionState("saving");

    const result = await updateMember({ nickname: nickname.trim() });
    setActionState("idle");

    if (result.ok) {
      onMemberUpdate(result.data);
      setNickname(result.data.nickname);
      setMessage("저장됐습니다.");
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
    setOpen(false);
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
      setConfirmWithdraw(false);
      setOpen(false);
      onSignedOut();
      return;
    }

    setMessage(result.error.message);
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={toggleOpen}
        className="flex h-12 items-center gap-3 rounded-full border border-stone-200 bg-white px-3 pr-5 text-sm font-black text-stone-950 shadow-sm transition hover:border-stone-400"
        aria-expanded={open}
      >
        <span className="grid size-8 place-items-center rounded-full bg-emerald-700 text-white">
          {getInitial(member.nickname)}
        </span>
        <span>{member.nickname}</span>
      </button>

      {open ? (
        <div className="absolute right-0 z-20 mt-3 w-[min(360px,calc(100vw-2rem))] rounded-[28px] border border-stone-200 bg-white p-4 shadow-[0_24px_80px_rgba(28,25,23,0.18)]">
          <div className="mb-4 flex items-start justify-between gap-4">
            <div>
              <h2 className="text-lg font-black tracking-tight">프로필</h2>
              <p className="mt-1 text-sm font-bold text-stone-500">
                {maskEmail(member.email)}
              </p>
            </div>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="grid size-9 place-items-center rounded-full bg-stone-100 text-sm font-black text-stone-700"
              aria-label="닫기"
            >
              ×
            </button>
          </div>

          <form onSubmit={handleNicknameSubmit} className="grid gap-2">
            <label htmlFor="profile-menu-nickname" className="text-sm font-black">
              닉네임
            </label>
            <input
              id="profile-menu-nickname"
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
            <p className="mt-3 text-sm font-bold text-stone-600">{message}</p>
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
