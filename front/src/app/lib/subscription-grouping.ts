import type { SubscriptionSummary } from "./subscription-conversations";

export type SubscriptionGroup = {
  label: string;
  items: SubscriptionSummary[];
};

const OTHER_LABEL = "기타";

// 도메인별로 구독을 묶고, 그룹 라벨 사전순(한국어) 으로 정렬한다.
// `domainLabel`이 비어 있는 항목은 "기타" 그룹으로 모이고 항상 마지막에 배치된다.
export function groupSubscriptionsByDomain(
  items: SubscriptionSummary[],
): SubscriptionGroup[] {
  const groups = new Map<string, SubscriptionSummary[]>();

  for (const item of items) {
    const trimmed = item.domainLabel?.trim();
    const label = trimmed && trimmed.length > 0 ? trimmed : OTHER_LABEL;
    const bucket = groups.get(label);
    if (bucket) {
      bucket.push(item);
    } else {
      groups.set(label, [item]);
    }
  }

  return Array.from(groups.entries())
    .map(([label, bucketItems]) => ({ label, items: bucketItems }))
    .sort((a, b) => {
      if (a.label === OTHER_LABEL && b.label !== OTHER_LABEL) {
        return 1;
      }
      if (b.label === OTHER_LABEL && a.label !== OTHER_LABEL) {
        return -1;
      }
      return a.label.localeCompare(b.label, "ko");
    });
}
