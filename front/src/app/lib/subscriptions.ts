export type DomainPreset = {
  id: number;
  name: string;
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

export type NotificationChannelId = "DISCORD_DM" | "TELEGRAM_DM" | "EMAIL";

export type ChannelPreset = {
  id: NotificationChannelId;
  label: string;
  actionLabel?: string;
  targetLabel?: string;
  targetPlaceholder?: string;
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
  notificationTargetAddress?: string;
};

const DOMAIN_COPY_BY_NAME: Record<
  string,
  Pick<DomainPreset, "label" | "example">
> = {
  "real-estate": {
    label: "부동산",
    example: "강남 투룸 전세 시세 바뀌면 알려줘",
  },
  "law-regulation": {
    label: "법률/규제",
    example: "개인정보 보호법 개정안 나오면 알려줘",
  },
  recruitment: {
    label: "채용",
    example: "넥슨 Java 3년 이상 채용 뜨면 알려줘",
  },
  auction: {
    label: "경매/희소매물",
    example: "나라장터 GPU 서버 입찰 공고 뜨면 알려줘",
  },
};

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
    actionLabel: "Discord 연결",
  },
  {
    id: "TELEGRAM_DM",
    label: "Telegram",
    actionLabel: "Telegram 연결",
  },
  {
    id: "EMAIL",
    label: "Email",
    targetLabel: "이메일 주소",
    targetPlaceholder: "user@example.com",
  },
];

export function buildDomainPresets(domains: DomainSummary[]): DomainPreset[] {
  return domains.map((domain) => {
    const copy = DOMAIN_COPY_BY_NAME[domain.name];

    return {
      id: domain.id,
      name: domain.name,
      label: copy?.label ?? domain.name,
      example: copy?.example ?? `${domain.name} 변경사항이 생기면 알려줘`,
    };
  });
}

export function validateSubscriptionForm(
  form: SubscriptionFormState,
  options: { selectedEndpointConnected?: boolean } = {},
): Record<string, string> {
  const errors: Record<string, string> = {};

  if (form.selectedDomainId <= 0) {
    errors.domainId = "감시 영역을 선택해 주세요.";
  }

  if (!form.query.trim()) {
    errors.query = "감시할 요청을 입력해 주세요.";
  }

  if (form.notificationChannel === "EMAIL") {
    const email = form.notificationTargetAddress.trim();
    if (!email && !options.selectedEndpointConnected) {
      errors.notificationTargetAddress = "알림 받을 이메일을 입력해 주세요.";
    } else if (email && !email.includes("@")) {
      errors.notificationTargetAddress = "올바른 이메일 형식으로 입력해 주세요.";
    }
  }

  return errors;
}

export function shouldShowEmailAddressInput(
  channel: NotificationChannelId,
  selectedEndpointConnected: boolean,
): boolean {
  return channel === "EMAIL" && !selectedEndpointConnected;
}

export function buildSubscriptionPayload(
  form: SubscriptionFormState,
): CreateSubscriptionRequest {
  const cadence = CADENCE_PRESETS.find((preset) => preset.id === form.cadenceId);
  const targetAddress = form.notificationTargetAddress.trim();

  const payload: CreateSubscriptionRequest = {
    domainId: form.selectedDomainId,
    query: form.query.trim(),
    cronExpr: cadence?.cronExpr ?? CADENCE_PRESETS[0].cronExpr,
    notificationChannel: form.notificationChannel,
  };

  if (form.notificationChannel === "EMAIL" && targetAddress) {
    payload.notificationTargetAddress = targetAddress;
  }

  return payload;
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
  | { ok: true; data: DomainPreset[] }
  | { ok: false; error: SubscriptionApiError };

export type CreateSubscriptionResult =
  | { ok: true; data: SubscriptionResponse }
  | { ok: false; error: SubscriptionApiError };

export type NotificationEndpointStatus = {
  channel: NotificationChannelId;
  connected: boolean;
  targetLabel: string | null;
};

export type NotificationConnectionResponse = {
  channel: NotificationChannelId;
  connected: boolean;
  targetLabel: string | null;
  connectUrl: string | null;
  authorizationUrl: string | null;
  message: string;
};

export type GetNotificationEndpointsResult =
  | { ok: true; data: NotificationEndpointStatus[] }
  | { ok: false; error: SubscriptionApiError };

export type NotificationConnectionResult =
  | { ok: true; data: NotificationConnectionResponse }
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

    return { ok: true, data: buildDomainPresets(readDomains(body)) };
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

export async function getNotificationEndpoints(
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<GetNotificationEndpointsResult> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}/api/notification-endpoints`, {
      method: "GET",
      credentials: "include",
    });
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return {
        ok: false,
        error: {
          code: getStringField(body, "code") ?? "REQUEST_FAILED",
          message:
            getStringField(body, "message") ??
            "알림 채널 연결 상태를 불러오지 못했습니다.",
        },
      };
    }

    return { ok: true, data: readNotificationEndpointStatuses(body) };
  } catch {
    return {
      ok: false,
      error: {
        code: "NETWORK_ERROR",
        message: "알림 채널 연결 상태를 불러올 수 없습니다.",
      },
    };
  }
}

export async function connectDiscordNotification(
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<NotificationConnectionResult> {
  return postNotificationConnection(
    "/api/notification-endpoints/discord/connect",
    "Discord 알림 연결 요청에 실패했습니다.",
    options,
  );
}

export async function startTelegramNotificationConnect(
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<NotificationConnectionResult> {
  return postNotificationConnection(
    "/api/notification-endpoints/telegram/connect",
    "Telegram 알림 연결 요청에 실패했습니다.",
    options,
  );
}

export async function disconnectNotificationEndpoint(
  channel: NotificationChannelId,
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<NotificationConnectionResult> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(
      `${baseUrl}/api/notification-endpoints/${channel}`,
      {
        method: "DELETE",
        credentials: "include",
      },
    );
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return {
        ok: false,
        error: {
          code: getStringField(body, "code") ?? "REQUEST_FAILED",
          message:
            getStringField(body, "message") ??
            "알림 채널 연결 해제 요청에 실패했습니다.",
        },
      };
    }

    return { ok: true, data: readNotificationConnectionResponse(body) };
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

async function postNotificationConnection(
  path: string,
  fallbackMessage: string,
  options: { baseUrl?: string; fetcher?: SubscriptionFetch },
): Promise<NotificationConnectionResult> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}${path}`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return {
        ok: false,
        error: {
          code: getStringField(body, "code") ?? "REQUEST_FAILED",
          message: getStringField(body, "message") ?? fallbackMessage,
        },
      };
    }

    return { ok: true, data: readNotificationConnectionResponse(body) };
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

function getBooleanField(value: unknown, field: string): boolean | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "boolean" ? fieldValue : null;
}

function getNullableStringField(value: unknown, field: string): string | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "string" ? fieldValue : null;
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

function readNotificationEndpointStatuses(
  value: unknown,
): NotificationEndpointStatus[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    const channel = getNotificationChannelField(item, "channel");
    const connected = getBooleanField(item, "connected");

    if (channel === null || connected === null) {
      return [];
    }

    return [
      {
        channel,
        connected,
        targetLabel: getNullableStringField(item, "targetLabel"),
      },
    ];
  });
}

function readNotificationConnectionResponse(
  value: unknown,
): NotificationConnectionResponse {
  return {
    channel: getNotificationChannelField(value, "channel") ?? "EMAIL",
    connected: getBooleanField(value, "connected") ?? false,
    targetLabel: getNullableStringField(value, "targetLabel"),
    connectUrl: getNullableStringField(value, "connectUrl"),
    authorizationUrl: getNullableStringField(value, "authorizationUrl"),
    message:
      getStringField(value, "message") ??
      "알림 채널 연결 상태가 업데이트되었습니다.",
  };
}

function getNotificationChannelField(
  value: unknown,
  field: string,
): NotificationChannelId | null {
  const fieldValue = getStringField(value, field);
  if (
    fieldValue === "DISCORD_DM" ||
    fieldValue === "TELEGRAM_DM" ||
    fieldValue === "EMAIL"
  ) {
    return fieldValue;
  }

  return null;
}
