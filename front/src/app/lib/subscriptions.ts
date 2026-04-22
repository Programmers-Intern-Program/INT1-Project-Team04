export type DomainPreset = {
  id: number;
  label: string;
  description: string;
  example: string;
};

export type CadencePresetId = "hourly" | "dailyMorning" | "weekdayMorning";

export type CadencePreset = {
  id: CadencePresetId;
  label: string;
  description: string;
  cronExpr: string;
};

export type ChannelPreset = {
  id: "discord" | "email" | "kakao";
  label: string;
  description: string;
};

export type SubscriptionFormState = {
  query: string;
  userId: string;
  domainId: string;
  cadenceId: CadencePresetId;
};

export type CreateSubscriptionRequest = {
  userId: number;
  domainId: number;
  query: string;
  cronExpr: string;
};

export const DOMAIN_PRESETS: DomainPreset[] = [
  {
    id: 1,
    label: "부동산",
    description: "지역, 거래 유형, 공고 변화를 감시합니다.",
    example: "강남 투룸 전세 시세 바뀌면 알려줘",
  },
  {
    id: 2,
    label: "법률/규제",
    description: "법안, 규제, 정책 개정 흐름을 추적합니다.",
    example: "AI 데이터 규제 개정안 나오면 알려줘",
  },
  {
    id: 3,
    label: "채용",
    description: "기업, 직무, 기술스택 기반 채용 오픈을 감시합니다.",
    example: "넥슨 Java 3년 이상 채용 뜨면 알려줘",
  },
  {
    id: 4,
    label: "경매/희소매물",
    description: "경매, 입찰, 한정판 매물 등 희소 신호를 추적합니다.",
    example: "나라장터 GPU 서버 입찰 공고 뜨면 알려줘",
  },
];

export const CADENCE_PRESETS: CadencePreset[] = [
  {
    id: "hourly",
    label: "매시간",
    description: "변화가 잦은 채용, 매물 감시에 적합",
    cronExpr: "0 0 * * * *",
  },
  {
    id: "dailyMorning",
    label: "매일 오전 9시",
    description: "하루 단위 브리핑에 적합",
    cronExpr: "0 0 9 * * *",
  },
  {
    id: "weekdayMorning",
    label: "평일 오전 9시",
    description: "업무용 법률, 규제, 입찰 추적에 적합",
    cronExpr: "0 0 9 * * MON-FRI",
  },
];

export const CHANNEL_PRESETS: ChannelPreset[] = [
  {
    id: "discord",
    label: "Discord",
    description: "현재 백엔드 알림 어댑터와 가장 가까운 채널",
  },
  {
    id: "email",
    label: "Email",
    description: "업무용 브리핑 채널",
  },
  {
    id: "kakao",
    label: "Kakao",
    description: "모바일 중심 알림 채널",
  },
];

export function validateSubscriptionForm(
  form: SubscriptionFormState,
): Record<string, string> {
  const errors: Record<string, string> = {};
  const userId = Number(form.userId);
  const domainId = Number(form.domainId);

  if (!form.query.trim()) {
    errors.query = "감시할 요청을 입력해 주세요.";
  }

  if (!Number.isInteger(userId) || userId < 1) {
    errors.userId = "사용자 ID는 1 이상의 숫자여야 합니다.";
  }

  if (!Number.isInteger(domainId) || domainId < 1) {
    errors.domainId = "도메인 ID는 1 이상의 숫자여야 합니다.";
  }

  return errors;
}

export function buildSubscriptionPayload(
  form: SubscriptionFormState,
): CreateSubscriptionRequest {
  const cadence = CADENCE_PRESETS.find((preset) => preset.id === form.cadenceId);

  return {
    userId: Number(form.userId),
    domainId: Number(form.domainId),
    query: form.query.trim(),
    cronExpr: cadence?.cronExpr ?? CADENCE_PRESETS[0].cronExpr,
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

export async function createSubscription(
  payload: CreateSubscriptionRequest,
  options: { baseUrl?: string; fetcher?: SubscriptionFetch } = {},
): Promise<CreateSubscriptionResult> {
  const baseUrl = (options.baseUrl ?? getApiBaseUrl()).replace(/\/$/, "");
  const fetcher = options.fetcher ?? fetch;

  try {
    const response = await fetcher(`${baseUrl}/api/subscriptions`, {
      method: "POST",
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
        message: "백엔드 서버에 연결할 수 없습니다.",
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
