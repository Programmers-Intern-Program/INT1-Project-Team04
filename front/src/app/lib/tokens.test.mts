import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  getTokenBalance,
  getTokenHistory,
  type TokenFetch,
} from "./tokens.ts";

describe("tokens API helpers", () => {
  it("loads balance with credentials and parses fields", async () => {
    const fetcher: TokenFetch = async (input, init) => {
      assert.equal(input, "http://api.test/api/tokens/balance/42");
      assert.equal(init?.method, "GET");
      assert.equal(init?.credentials, "include");
      return new Response(
        JSON.stringify({
          userId: 42,
          balance: 100,
          totalGranted: 200,
          totalUsed: 100,
          lastUpdatedAt: "2026-05-14T10:00:00",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    };

    const result = await getTokenBalance(42, {
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.equal(result.ok, true);
    if (result.ok) {
      assert.equal(result.data.balance, 100);
      assert.equal(result.data.totalGranted, 200);
      assert.equal(result.data.totalUsed, 100);
      assert.equal(result.data.lastUpdatedAt, "2026-05-14T10:00:00");
    }
  });

  it("returns zeros when balance fields are missing or wrong type", async () => {
    const fetcher: TokenFetch = async () =>
      new Response(JSON.stringify({ userId: 1, balance: "oops" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });

    const result = await getTokenBalance(1, {
      baseUrl: "http://api.test",
      fetcher,
    });

    assert.equal(result.ok, true);
    if (result.ok) {
      assert.equal(result.data.balance, 0);
      assert.equal(result.data.totalGranted, 0);
      assert.equal(result.data.lastUpdatedAt, null);
    }
  });

  it("includes limit query param when fetching history", async () => {
    let capturedUrl = "";
    const fetcher: TokenFetch = async (input) => {
      capturedUrl = String(input);
      return new Response(JSON.stringify({ history: [] }), { status: 200 });
    };

    await getTokenHistory(7, 5, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.equal(capturedUrl, "http://api.test/api/tokens/history/7?limit=5");
  });

  it("falls back to default limit when provided value is invalid", async () => {
    let capturedUrl = "";
    const fetcher: TokenFetch = async (input) => {
      capturedUrl = String(input);
      return new Response(JSON.stringify({ history: [] }), { status: 200 });
    };

    await getTokenHistory(7, Number.NaN, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.equal(capturedUrl, "http://api.test/api/tokens/history/7?limit=20");
  });

  it("parses history entries and drops malformed items", async () => {
    const fetcher: TokenFetch = async () =>
      new Response(
        JSON.stringify({
          history: [
            {
              id: "h1",
              type: "USE",
              amount: 10,
              balanceBefore: 100,
              balanceAfter: 90,
              description: "구독 실행",
              createdAt: "2026-05-14T09:00:00",
            },
            {
              // id 누락 → 드롭
              type: "GRANT",
              amount: 50,
            },
            {
              id: "h2",
              type: "GRANT",
              amount: 50,
              balanceBefore: 90,
              balanceAfter: 140,
              description: "지급",
              createdAt: "2026-05-14T09:01:00",
            },
          ],
        }),
        { status: 200 },
      );

    const result = await getTokenHistory(1, 20, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.equal(result.ok, true);
    if (result.ok) {
      assert.equal(result.data.length, 2);
      assert.equal(result.data[0]?.id, "h1");
      assert.equal(result.data[1]?.id, "h2");
    }
  });

  it("returns an empty array when history field is missing", async () => {
    const fetcher: TokenFetch = async () =>
      new Response(JSON.stringify({ irrelevant: true }), { status: 200 });

    const result = await getTokenHistory(1, 20, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.equal(result.ok, true);
    if (result.ok) {
      assert.deepEqual(result.data, []);
    }
  });

  it("maps 401 to unauthenticated for balance and history", async () => {
    const fetcher: TokenFetch = async () =>
      new Response(
        JSON.stringify({ code: "UNAUTHENTICATED", message: "로그인 필요" }),
        { status: 401 },
      );

    const balance = await getTokenBalance(1, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.deepEqual(balance, { ok: false, status: "unauthenticated" });

    const history = await getTokenHistory(1, 20, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.deepEqual(history, { ok: false, status: "unauthenticated" });
  });

  it("maps non-401 errors to error result with code and message", async () => {
    const fetcher: TokenFetch = async () =>
      new Response(
        JSON.stringify({ code: "FORBIDDEN", message: "권한이 없습니다." }),
        { status: 403 },
      );

    const result = await getTokenBalance(1, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.equal(result.ok, false);
    if (!result.ok && result.status === "error") {
      assert.equal(result.error.code, "FORBIDDEN");
      assert.equal(result.error.message, "권한이 없습니다.");
    } else {
      assert.fail("expected error result");
    }
  });

  it("maps network errors to NETWORK_ERROR", async () => {
    const fetcher: TokenFetch = async () => {
      throw new Error("ECONNREFUSED");
    };

    const result = await getTokenBalance(1, {
      baseUrl: "http://api.test",
      fetcher,
    });
    assert.equal(result.ok, false);
    if (!result.ok && result.status === "error") {
      assert.equal(result.error.code, "NETWORK_ERROR");
    } else {
      assert.fail("expected error result");
    }
  });
});
