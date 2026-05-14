export type TokenBalance = {
  userId: number;
  balance: number;
  totalGranted: number;
  totalUsed: number;
  lastUpdatedAt: string | null;
};

export type TokenUsageType = "USE" | "GRANT" | "REFUND" | string;

export type TokenUsageEntry = {
  id: string;
  type: TokenUsageType;
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  description: string;
  createdAt: string | null;
};

export type TokenApiError = {
  code: string;
  message: string;
};

export type TokenResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: "unauthenticated" }
  | { ok: false; status: "error"; error: TokenApiError };

export type TokenFetch = (input: string, init?: RequestInit) => Promise<Response>;

type Options = {
  baseUrl?: string;
  fetcher?: TokenFetch;
};

export function getTokenApiBaseUrl(): string {
  return (
    process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ??
    "http://localhost:8080"
  );
}

export async function getTokenBalance(
  userId: number,
  options: Options = {},
): Promise<TokenResult<TokenBalance>> {
  const result = await requestJson<unknown>(
    `/api/tokens/balance/${userId}`,
    { method: "GET" },
    options,
  );

  if (!result.ok) {
    return result;
  }

  return { ok: true, data: readTokenBalance(result.data) };
}

export async function getTokenHistory(
  userId: number,
  limit = 20,
  options: Options = {},
): Promise<TokenResult<TokenUsageEntry[]>> {
  const safeLimit = Number.isFinite(limit) && limit > 0 ? Math.floor(limit) : 20;
  const result = await requestJson<unknown>(
    `/api/tokens/history/${userId}?limit=${safeLimit}`,
    { method: "GET" },
    options,
  );

  if (!result.ok) {
    return result;
  }

  return { ok: true, data: readTokenHistory(result.data) };
}

async function requestJson<T>(
  path: string,
  init: RequestInit,
  options: Options,
): Promise<TokenResult<T>> {
  const baseUrl = (options.baseUrl ?? getTokenApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}${path}`, {
      ...init,
      credentials: "include",
    });
    const body: unknown = await response.json().catch(() => null);

    if (response.status === 401) {
      return { ok: false, status: "unauthenticated" };
    }

    if (!response.ok) {
      return {
        ok: false,
        status: "error",
        error: {
          code: getStringField(body, "code") ?? "REQUEST_FAILED",
          message:
            getStringField(body, "message") ?? "요청에 실패했습니다.",
        },
      };
    }

    return { ok: true, data: body as T };
  } catch {
    return {
      ok: false,
      status: "error",
      error: {
        code: "NETWORK_ERROR",
        message: "서버에 연결할 수 없습니다.",
      },
    };
  }
}

function readTokenBalance(value: unknown): TokenBalance {
  return {
    userId: getNumberField(value, "userId") ?? 0,
    balance: getNumberField(value, "balance") ?? 0,
    totalGranted: getNumberField(value, "totalGranted") ?? 0,
    totalUsed: getNumberField(value, "totalUsed") ?? 0,
    lastUpdatedAt: getStringField(value, "lastUpdatedAt"),
  };
}

function readTokenHistory(value: unknown): TokenUsageEntry[] {
  if (!value || typeof value !== "object") {
    return [];
  }
  const history = (value as { history?: unknown }).history;
  if (!Array.isArray(history)) {
    return [];
  }

  return history.flatMap((item) => {
    const id = getStringField(item, "id");
    const amount = getNumberField(item, "amount");
    if (id === null || amount === null) {
      return [];
    }
    return [
      {
        id,
        type: getStringField(item, "type") ?? "USE",
        amount,
        balanceBefore: getNumberField(item, "balanceBefore") ?? 0,
        balanceAfter: getNumberField(item, "balanceAfter") ?? 0,
        description: getStringField(item, "description") ?? "",
        createdAt: getStringField(item, "createdAt"),
      },
    ];
  });
}

function getStringField(value: unknown, field: string): string | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }
  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "string" ? fieldValue : null;
}

function getNumberField(value: unknown, field: string): number | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }
  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "number" ? fieldValue : null;
}
