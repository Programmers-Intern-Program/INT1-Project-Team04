export type OAuthProvider = "kakao" | "google" | "discord";

export type Member = {
  id: number;
  email: string;
  nickname: string;
  providers: string[];
};

export type AuthApiError = {
  code: string;
  message: string;
};

export type AuthResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: "unauthenticated" }
  | { ok: false; status: "error"; error: AuthApiError };

export type AuthFetch = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

type AuthOptions = {
  baseUrl?: string;
  fetcher?: AuthFetch;
};

export function buildOAuthLoginUrl(
  provider: OAuthProvider,
  baseUrl = getApiBaseUrl(),
): string {
  return `${baseUrl.replace(/\/$/, "")}/api/auth/oauth/${provider}/authorize`;
}

export function getApiBaseUrl(): string {
  return (
    process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ??
    "http://localhost:8080"
  );
}

export async function getCurrentMember(
  options: AuthOptions = {},
): Promise<AuthResult<Member>> {
  return requestJson<Member>("/api/auth/me", { method: "GET" }, options);
}

export async function updateMember(
  payload: { nickname: string },
  options: AuthOptions = {},
): Promise<AuthResult<Member>> {
  return requestJson<Member>(
    "/api/members/me",
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    },
    options,
  );
}

export async function logout(
  options: AuthOptions = {},
): Promise<AuthResult<null>> {
  return requestJson<null>("/api/auth/logout", { method: "POST" }, options);
}

export async function withdrawMember(
  options: AuthOptions = {},
): Promise<AuthResult<null>> {
  return requestJson<null>("/api/members/me", { method: "DELETE" }, options);
}

async function requestJson<T>(
  path: string,
  init: RequestInit,
  options: AuthOptions,
): Promise<AuthResult<T>> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
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
          message: getStringField(body, "message") ?? "요청에 실패했습니다.",
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

function getStringField(value: unknown, field: string): string | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "string" ? fieldValue : null;
}
