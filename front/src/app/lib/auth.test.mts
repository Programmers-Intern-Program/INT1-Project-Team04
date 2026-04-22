import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  buildOAuthLoginUrl,
  getCurrentMember,
  logout,
  updateMember,
  withdrawMember,
  type AuthFetch,
} from "./auth.ts";

describe("auth API helpers", () => {
  it("builds backend OAuth login URLs for supported providers", () => {
    assert.equal(
      buildOAuthLoginUrl("kakao", "http://api.test/"),
      "http://api.test/api/auth/oauth/kakao/authorize",
    );
    assert.equal(
      buildOAuthLoginUrl("google", "http://api.test"),
      "http://api.test/api/auth/oauth/google/authorize",
    );
    assert.equal(
      buildOAuthLoginUrl("discord", "http://api.test"),
      "http://api.test/api/auth/oauth/discord/authorize",
    );
  });

  it("loads the current member with credentials", async () => {
    const fetcher: AuthFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/auth/me");
      assert.equal(init?.credentials, "include");
      return new Response(
        JSON.stringify({
          id: 1,
          email: "user@example.com",
          nickname: "사용자",
          providers: ["GOOGLE"],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await getCurrentMember({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.equal(result.ok, true);
    assert.equal(result.ok ? result.data.nickname : "", "사용자");
  });

  it("maps unauthenticated current-member responses", async () => {
    const fetcher: AuthFetch = async () =>
      new Response(
        JSON.stringify({
          code: "UNAUTHENTICATED",
          message: "로그인이 필요합니다.",
        }),
        { status: 401 },
      );

    const result = await getCurrentMember({
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.deepEqual(result, { ok: false, status: "unauthenticated" });
  });

  it("updates nickname, logs out, and withdraws with credentials", async () => {
    const calls: Array<[string, RequestInit | undefined]> = [];
    const fetcher: AuthFetch = async (input, init) => {
      calls.push([input, init]);
      return new Response(
        JSON.stringify({
          id: 1,
          email: "user@example.com",
          nickname: "새닉네임",
          providers: ["GOOGLE"],
        }),
        {
          status:
            input.endsWith("/api/members/me") && init?.method === "DELETE"
              ? 204
              : 200,
          headers: { "Content-Type": "application/json" },
        },
      );
    };

    await updateMember(
      { nickname: "새닉네임" },
      { baseUrl: "http://api.test", fetcher },
    );
    await logout({ baseUrl: "http://api.test", fetcher });
    await withdrawMember({ baseUrl: "http://api.test", fetcher });

    assert.deepEqual(
      calls.map(([input, init]) => [input, init?.method, init?.credentials]),
      [
        ["http://api.test/api/members/me", "PATCH", "include"],
        ["http://api.test/api/auth/logout", "POST", "include"],
        ["http://api.test/api/members/me", "DELETE", "include"],
      ],
    );
  });
});
