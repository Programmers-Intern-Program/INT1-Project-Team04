package com.back.domain.application.service.subscriptionconversation;

import com.back.domain.application.port.out.NormalizeSubscriptionDraftPort;
import com.back.domain.application.result.ParsedTask;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * [Application Helper] AI 파서 결과를 구독 생성 초안으로 정규화하는 컴포넌트
 * * 도메인별 실행 계약(region, condition, toolName, missingFields 등)은 MCP 서버 정규화 결과만 사용한다.
 * * notificationChannel 처럼 사용자 계정/전달 설정에 가까운 필드는 백엔드에서 계속 보강한다.
 * * cronExpr 는 사용자 알림 빈도가 아니라 API 벌크 데이터 갱신 확인용 내부 스케줄로 기본값을 사용한다.
 * * MCP 정규화가 실패하면 백엔드 로컬 파싱으로 우회하지 않고 MCP_REQUEST_FAILED 로 드러낸다.
 */
@Component
public class ParsedTaskNormalizer {

    private static final String DEFAULT_INTERNAL_CHECK_CRON = "0 0 * * * *";

    private final NormalizeSubscriptionDraftPort normalizeSubscriptionDraftPort;

    public ParsedTaskNormalizer(NormalizeSubscriptionDraftPort normalizeSubscriptionDraftPort) {
        this.normalizeSubscriptionDraftPort = normalizeSubscriptionDraftPort;
    }

    public SubscriptionDraft normalize(ParsedTask task, String userMessage) {
        return normalize(task, userMessage, null);
    }

    public SubscriptionDraft normalize(ParsedTask task, String userMessage, SubscriptionDraft previousDraft) {
        return mergeConversationFields(normalizeDomain(task, userMessage, previousDraft), task, userMessage, previousDraft);
    }

    private DomainNormalizedSubscriptionDraft normalizeDomain(
            ParsedTask task,
            String userMessage,
            SubscriptionDraft previousDraft
    ) {
        if (normalizeSubscriptionDraftPort == null) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
        return normalizeSubscriptionDraftPort.normalize(task, userMessage, previousDraft)
                .orElseThrow(() -> new ApiException(ErrorCode.MCP_REQUEST_FAILED));
    }

    // MCP는 도메인 구조화만 담당한다.
    // 백엔드는 parser 기본값을 그대로 믿지 않고, 사용자 원문에 명시된 주기/채널만 확정값으로 합성한다.
    private SubscriptionDraft mergeConversationFields(
            DomainNormalizedSubscriptionDraft domainDraft,
            ParsedTask task,
            String userMessage,
            SubscriptionDraft previousDraft
    ) {
        String domainName = domainDraft.domainName();

        boolean canReusePrevious = canReusePreviousDraft(previousDraft, domainName);
        List<String> missing = new ArrayList<>(domainDraft.missingFields());
        boolean unsupported = containsUnsupported(missing);

        // unsupported 계열은 도메인 자체가 실행 불가하므로 channel 추가 질문을 붙이지 않는다.
        // 사용자는 먼저 지원 가능한 도메인/자료유형으로 요청을 바꿔야 한다.
        String cronExpr = null;
        String channel = null;
        String targetAddress = null;
        if (!unsupported) {
            cronExpr = DEFAULT_INTERNAL_CHECK_CRON;

            channel = explicitChannel(userMessage);
            if (channel == null && canReusePrevious) {
                channel = previousDraft.notificationChannel();
            }
            if (channel == null) {
                addMissing(missing, "notificationChannel");
            }

            targetAddress = canReusePrevious ? previousDraft.notificationTargetAddress() : null;
        }

        String query = !isBlank(domainDraft.query()) ? domainDraft.query() : task.query();
        String intent = !isBlank(domainDraft.intent()) ? domainDraft.intent() : task.intent();
        double confidence = domainDraft.confidence() > 0 ? domainDraft.confidence() : task.confidence();

        return new SubscriptionDraft(
                query,
                domainName,
                intent,
                domainDraft.toolName(),
                domainDraft.monitoringParams(),
                cronExpr,
                channel,
                targetAddress,
                missing,
                assistantQuestion(missing, domainDraft.assistantMessage()),
                confidence
        );
    }

    private String explicitChannel(String userMessage) {
        String text = lower(userMessage);
        if (text.contains("텔레그램") || text.contains("telegram")) {
            return "TELEGRAM_DM";
        }
        if (text.contains("디스코드") || text.contains("디코") || text.contains("discord")) {
            return "DISCORD_DM";
        }
        if (text.contains("이메일") || text.contains("메일") || text.contains("email")) {
            return "EMAIL";
        }
        return null;
    }

    private String assistantQuestion(List<String> missing) {
        if (missing.contains("region")) {
            return "어느 지역의 아파트 매매 실거래가를 확인할까요?";
        }
        if (missing.contains("dealType")) {
            return "아파트 가격은 매매/전세/월세 중 어떤 기준인가요? 현재는 매매 실거래가 알림만 만들 수 있어요.";
        }
        if (missing.contains("condition")) {
            return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등";
        }
        if (missing.contains("notificationChannel")) {
            return "알림을 받을 채널을 선택해 주세요. Telegram, Discord, Email 중 무엇으로 받을까요?";
        }
        return "";
    }

    private String assistantQuestion(List<String> missing, String mcpQuestion) {
        if (!isBlank(mcpQuestion)) {
            return mcpQuestion;
        }
        return assistantQuestion(missing);
    }

    private boolean containsUnsupported(List<String> missing) {
        return missing.contains("unsupportedDomain")
                || missing.contains("unsupportedIntent")
                || missing.contains("unsupportedCapability");
    }

    private void addMissing(List<String> missing, String field) {
        if (!missing.contains(field)) {
            missing.add(field);
        }
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean canReusePreviousDraft(SubscriptionDraft previousDraft, String domainName) {
        return previousDraft != null && !isBlank(domainName) && domainName.equals(previousDraft.domainName());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
