"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS: ReadonlyArray<{ href: string; label: string }> = [
  { href: "/", label: "알림 만들기" },
  { href: "/subscriptions", label: "구독 목록" },
  { href: "/settings", label: "설정" },
];

export function NavTabs() {
  const pathname = usePathname();

  return (
    <nav aria-label="주요 메뉴" className="flex items-center gap-1">
      {TABS.map((tab) => {
        const active = isActive(pathname, tab.href);
        return (
          <Link
            key={tab.href}
            href={tab.href}
            aria-current={active ? "page" : undefined}
            className={
              active
                ? "rounded-full bg-stone-950 px-4 py-2 text-sm font-black text-white"
                : "rounded-full px-4 py-2 text-sm font-black text-stone-600 transition hover:text-stone-950"
            }
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}

function isActive(pathname: string | null, href: string): boolean {
  if (!pathname) {
    return false;
  }
  if (href === "/") {
    return pathname === "/";
  }
  return pathname === href || pathname.startsWith(`${href}/`);
}
