export type NotificationChannelId = "DISCORD_DM" | "TELEGRAM_DM" | "EMAIL";

export type SubscriptionApiError = {
  code: string;
  message: string;
};

export type SubscriptionConversationFetch = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

export type ConversationActionType =
  | "SELECT_CADENCE"
  | "SELECT_CHANNEL"
  | "CONFIRM_SUBSCRIPTION"
  | "CANCEL_CONVERSATION";

export type ConversationActionRequest = {
  type: ConversationActionType;
  value: string;
};

export type ConversationActionOption = ConversationActionRequest & {
  label: string;
  connected?: boolean;
  requiresConnection?: boolean;
};

export type ConversationDraft = {
  query: string;
  domainId: number;
  domainLabel: string;
  intent: string;
  toolName: string;
  monitoringParams: Record<string, string>;
  cronExpr: string;
  cadenceLabel: string;
  notificationChannel: NotificationChannelId;
  channelLabel: string;
  recipientLabel: string;
};

export type ConversationResponse = {
  conversationId: string;
  status: "NEEDS_INPUT" | "READY_FOR_CONFIRMATION" | "CREATED" | "CANCELLED";
  assistantMessage: string;
  draft?: ConversationDraft | null;
  actions?: ConversationActionOption[];
  subscription?: {
    id: string;
    nextRun: string;
  } | null;
};

export type SubscriptionSummary = {
  id: string;
  query: string;
  domainLabel: string;
  cadenceLabel: string;
  notificationChannel: NotificationChannelId | null;
  channelLabel: string;
  nextRun: string | null;
  active: boolean;
};

export type ConversationResult =
  | { ok: true; data: ConversationResponse }
  | { ok: false; error: SubscriptionApiError };

export type SubscriptionSummariesResult =
  | { ok: true; data: SubscriptionSummary[] }
  | { ok: false; error: SubscriptionApiError };

export type DeleteSubscriptionResult =
  | { ok: true }
  | { ok: false; error: SubscriptionApiError };

export function getConversationApiBaseUrl(): string {
  return (
    process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ??
    "http://localhost:8080"
  );
}

export async function sendConversationMessage(
  payload: { conversationId?: string; message: string },
  options: { baseUrl?: string; fetcher?: SubscriptionConversationFetch } = {},
): Promise<ConversationResult> {
  return postConversation(
    {
      conversationId: payload.conversationId,
      message: payload.message,
    },
    options,
  );
}

export async function sendConversationAction(
  payload: { conversationId: string; action: ConversationActionRequest },
  options: { baseUrl?: string; fetcher?: SubscriptionConversationFetch } = {},
): Promise<ConversationResult> {
  return postConversation(
    {
      conversationId: payload.conversationId,
      action: payload.action,
    },
    options,
  );
}

export async function getSubscriptionSummaries(
  options: { baseUrl?: string; fetcher?: SubscriptionConversationFetch } = {},
): Promise<SubscriptionSummariesResult> {
  const baseUrl = (options.baseUrl ?? getConversationApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}/api/subscriptions`, {
      method: "GET",
      credentials: "include",
    });
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return { ok: false, error: readError(body, "구독 목록을 불러오지 못했습니다.") };
    }

    return { ok: true, data: readSubscriptionSummaries(body) };
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

export async function deleteSubscriptionSummary(
  subscriptionId: string,
  options: { baseUrl?: string; fetcher?: SubscriptionConversationFetch } = {},
): Promise<DeleteSubscriptionResult> {
  const baseUrl = (options.baseUrl ?? getConversationApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}/api/subscriptions/${subscriptionId}`, {
      method: "DELETE",
      credentials: "include",
    });
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return { ok: false, error: readError(body, "구독 삭제 요청에 실패했습니다.") };
    }

    return { ok: true };
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

async function postConversation(
  payload: Record<string, unknown>,
  options: { baseUrl?: string; fetcher?: SubscriptionConversationFetch },
): Promise<ConversationResult> {
  const baseUrl = (options.baseUrl ?? getConversationApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(
      `${baseUrl}/api/subscription-conversations/messages`,
      {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    );
    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      return { ok: false, error: readError(body, "대화 요청에 실패했습니다.") };
    }

    return { ok: true, data: body as ConversationResponse };
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

function readSubscriptionSummaries(value: unknown): SubscriptionSummary[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    const id = getStringField(item, "id");
    const query = getStringField(item, "query");
    const domainLabel = getStringField(item, "domainLabel");
    const cadenceLabel = getStringField(item, "cadenceLabel");
    const channelLabel = getStringField(item, "channelLabel");
    const active = getBooleanField(item, "active");

    if (
      id === null ||
      query === null ||
      domainLabel === null ||
      cadenceLabel === null ||
      channelLabel === null ||
      active === null
    ) {
      return [];
    }

    return [
      {
        id,
        query,
        domainLabel,
        cadenceLabel,
        notificationChannel: getNotificationChannelField(item, "notificationChannel"),
        channelLabel,
        nextRun: getNullableStringField(item, "nextRun"),
        active,
      },
    ];
  });
}

function readError(value: unknown, fallbackMessage: string): SubscriptionApiError {
  return {
    code: getStringField(value, "code") ?? "REQUEST_FAILED",
    message: getStringField(value, "message") ?? fallbackMessage,
  };
}

function getStringField(value: unknown, field: string): string | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "string" ? fieldValue : null;
}

function getNullableStringField(value: unknown, field: string): string | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "string" ? fieldValue : null;
}

function getBooleanField(value: unknown, field: string): boolean | null {
  if (!value || typeof value !== "object" || !(field in value)) {
    return null;
  }

  const fieldValue = value[field as keyof typeof value];
  return typeof fieldValue === "boolean" ? fieldValue : null;
}

function getNotificationChannelField(
  value: unknown,
  field: string,
): NotificationChannelId | null {
  const channel = getStringField(value, field);
  if (
    channel === "DISCORD_DM" ||
    channel === "TELEGRAM_DM" ||
    channel === "EMAIL"
  ) {
    return channel;
  }
  return null;
}
