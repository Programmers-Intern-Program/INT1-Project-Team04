"use client";

import { createContext, useContext } from "react";

import type { Member } from "../../lib/auth";

export type AuthContextValue = {
  member: Member;
  setMember: (member: Member) => void;
};

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuthContext(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error(
      "useAuthContext는 (app) 라우트 그룹의 AuthContext.Provider 내부에서만 호출할 수 있어요.",
    );
  }
  return value;
}

export function useAuthMember(): Member {
  return useAuthContext().member;
}
