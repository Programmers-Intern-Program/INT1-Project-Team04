export type DomainPreset = {
  id: number;
  label: string;
  example: string;
};

export type DomainSummary = {
  id: number;
  name: string;
};

export type CadencePresetId = "hourly" | "dailyMorning" | "weekdayMorning";

export type CadencePreset = {
  id: CadencePresetId;
  label: string;
  cronExpr: string;
};

export type ChannelPreset = {
  id: "DISCORD_DM" | "TELEGRAM_DM" | "EMAIL";
  label: string;
  targetLabel: string;
  targetPlaceholder: string;
};

export type SubscriptionFormState = {
  query: string;
  selectedDomainId: number;
  cadenceId: CadencePresetId;
  notificationChannel: ChannelPreset["id"];
  notificationTargetAddress: string;
};

export type CreateSubscriptionRequest = {
  domainId: number;
  query: string;
  cronExpr: string;
  notificationChannel: ChannelPreset["id"];
  notificationTargetAddress: string;
};

export const DOMAIN_PRESETS: DomainPreset[] = [
  {
    id: 1,
    label: "부동산",
    example: "강남 투룸 전세 시세 바뀌면 알려줘",
  },
  {
    id: 2,
    label: "법률/규제",
    example: "개인정보 보호법 개정안 나오면 알려줘",
  },
  {
    id: 3,
    label: "채용",
    example: "넥슨 Java 3년 이상 채용 뜨면 알려줘",
  },
  {
    id: 4,
    label: "경매/희소매물",
    example: "나라장터 GPU 서버 입찰 공고 뜨면 알려줘",
  },
];

export const CADENCE_PRESETS: CadencePreset[] = [
  {
    id: "hourly",
    label: "매시간",
    cronExpr: "0 0 * * * *",
  },
  {
    id: "dailyMorning",
    label: "매일 오전 9시",
    cronExpr: "0 0 9 * * *",
  },
  {
    id: "weekdayMorning",
    label: "평일 오전 9시",
    cronExpr: "0 0 9 * * MON-FRI",
  },
];

export const CHANNEL_PRESETS: ChannelPreset[] = [
  {
    id: "DISCORD_DM",
    label: "Discord",
    targetLabel: "Discord 사용자 ID",
    targetPlaceholder: "987654321",
  },
  {
    id: "TELEGRAM_DM",
    label: "Telegram",
    targetLabel: "Telegram chat_id",
    targetPlaceholder: "123456789",
  },
  {
    id: "EMAIL",
    label: "Email",
    targetLabel: "이메일 주소",
    targetPlaceholder: "user@example.com",
  },
];

export function validateSubscriptionForm(
  form: SubscriptionFormState,
): Record<string, string> {
  const errors: Record<string, string> = {};

  if (!form.query.trim()) {
    errors.query = "감시할 요청을 입력해 주세요.";
  }

  if (!form.notificationTargetAddress.trim()) {
    errors.notificationTargetAddress = "알림을 받을 대상을 입력해 주세요.";
  }

  return errors;
}

export function buildSubscriptionPayload(
  form: SubscriptionFormState,
): CreateSubscriptionRequest {
  const cadence = CADENCE_PRESETS.find((preset) => preset.id === form.cadenceId);

  return {
    domainId: form.selectedDomainId,
    query: form.query.trim(),
    cronExpr: cadence?.cronExpr ?? CADENCE_PRESETS[0].cronExpr,
    notificationChannel: form.notificationChannel,
    notificationTargetAddress: form.notificationTargetAddress.trim(),
  };
}

export type SubscriptionResponse = {
  id: string;
  userId: number;
  domainId: number;
  query: string;
  active: boolean;
  createdAt: string;
  scheduleId: string;
  cronExpr: string;
  nextRun: string;
};

export type SubscriptionApiError = {
  code: string;
  message: string;
};

export type GetDomainsResult =
  | { ok: true; data: DomainSummary[] }
  | { ok: false; error: SubscriptionApiError };

export type CreateSubscriptionResult =
  | { ok: true; data: SubscriptionResponse }
  | { ok: false; error: SubscriptionApiError };

export type SubscriptionFetch = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

export function getApiBaseUrl(): string {
  return (
    process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ??
    "http://localhost:8080"
  );
}

export async function getDomains(
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<GetDomainsResult> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}/api/domains`, {
      method: "GET",
    });
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return {
        ok: false,
        error: {
          code: getStringField(body, "code") ?? "REQUEST_FAILED",
          message:
            getStringField(body, "message") ??
            "도메인 목록을 불러오지 못했습니다.",
        },
      };
    }

    return { ok: true, data: readDomains(body) };
  } catch {
    return {
      ok: false,
      error: {
        code: "NETWORK_ERROR",
        message: "감시 영역을 불러올 수 없습니다.",
      },
    };
  }
}

export async function createSubscription(
  payload: CreateSubscriptionRequest,
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<CreateSubscriptionResult> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}/api/subscriptions`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return {
        ok: false,
        error: {
          code: getStringField(body, "code") ?? "REQUEST_FAILED",
          message:
            getStringField(body, "message") ??
            "구독 등록 요청에 실패했습니다.",
        },
      };
    }

    return { ok: true, data: body as SubscriptionResponse };
  } catch {
    return {
      ok: false,
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

function getNumberField(value: unknown, field: string): number | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "number" ? fieldValue : null;
}

function readDomains(value: unknown): DomainSummary[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    const id = getNumberField(item, "id");
    const name = getStringField(item, "name");

    if (id === null || name === null) {
      return [];
    }

    return [{ id, name }];
  });
}
