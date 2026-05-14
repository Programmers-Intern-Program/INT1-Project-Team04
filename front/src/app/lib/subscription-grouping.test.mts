import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { groupSubscriptionsByDomain } from "./subscription-grouping.ts";
import type { SubscriptionSummary } from "./subscription-conversations.ts";

function makeSummary(
  overrides: Partial<SubscriptionSummary> & { id: string },
): SubscriptionSummary {
  return {
    id: overrides.id,
    query: overrides.query ?? "강남 투룸 전세",
    domainLabel: overrides.domainLabel ?? "부동산",
    cadenceLabel: overrides.cadenceLabel ?? "매시간",
    notificationChannel: overrides.notificationChannel ?? "TELEGRAM_DM",
    channelLabel: overrides.channelLabel ?? "Telegram",
    nextRun: overrides.nextRun ?? null,
    active: overrides.active ?? true,
  };
}

describe("groupSubscriptionsByDomain", () => {
  it("returns an empty array for empty input", () => {
    assert.deepEqual(groupSubscriptionsByDomain([]), []);
  });

  it("groups subscriptions with the same domainLabel together", () => {
    const groups = groupSubscriptionsByDomain([
      makeSummary({ id: "a", domainLabel: "부동산" }),
      makeSummary({ id: "b", domainLabel: "채용" }),
      makeSummary({ id: "c", domainLabel: "부동산" }),
    ]);

    assert.equal(groups.length, 2);
    const realEstate = groups.find((group) => group.label === "부동산");
    const recruitment = groups.find((group) => group.label === "채용");
    assert.ok(realEstate, "부동산 그룹이 있어야 함");
    assert.ok(recruitment, "채용 그룹이 있어야 함");
    assert.deepEqual(
      realEstate!.items.map((item) => item.id),
      ["a", "c"],
    );
    assert.deepEqual(
      recruitment!.items.map((item) => item.id),
      ["b"],
    );
  });

  it("sorts non-other groups by Korean locale order", () => {
    const groups = groupSubscriptionsByDomain([
      makeSummary({ id: "1", domainLabel: "채용" }),
      makeSummary({ id: "2", domainLabel: "부동산" }),
      makeSummary({ id: "3", domainLabel: "경매" }),
    ]);
    assert.deepEqual(
      groups.map((group) => group.label),
      ["경매", "부동산", "채용"],
    );
  });

  it("places items with missing or empty domainLabel into 기타 group at the end", () => {
    const groups = groupSubscriptionsByDomain([
      makeSummary({ id: "a", domainLabel: "" }),
      makeSummary({ id: "b", domainLabel: "부동산" }),
      makeSummary({ id: "c", domainLabel: "   " }),
    ]);

    assert.equal(groups[0]?.label, "부동산");
    assert.equal(groups[1]?.label, "기타");
    assert.deepEqual(
      groups[1]?.items.map((item) => item.id),
      ["a", "c"],
    );
  });

  it("keeps insertion order for items within the same group", () => {
    const groups = groupSubscriptionsByDomain([
      makeSummary({ id: "first", domainLabel: "부동산" }),
      makeSummary({ id: "second", domainLabel: "부동산" }),
      makeSummary({ id: "third", domainLabel: "부동산" }),
    ]);

    assert.deepEqual(
      groups[0]?.items.map((item) => item.id),
      ["first", "second", "third"],
    );
  });
});
