package com.back.domain.application.service.subscriptionconversation;

import com.back.domain.application.result.ParsedTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ParsedTaskNormalizer {

    private static final Pattern REGION = Pattern.compile(
            "([가-힣]+(?:특별자치시|특별자치도|특별시|광역시|시|군|구|읍|면|동)|서울|부산|대구|인천|광주|대전|울산|세종|제주)"
    );
    private static final List<String> REGION_ALIASES = List.of(
            "강남", "서초", "송파", "마포", "성남", "안산"
    );

    private final DomainCapabilityRegistry registry;

    public SubscriptionDraft normalize(ParsedTask task, String userMessage) {
        return normalize(task, userMessage, null);
    }

    public SubscriptionDraft normalize(ParsedTask task, String userMessage, SubscriptionDraft previousDraft) {
        String domainName = canonicalDomainName(task.domainName());
        if (isBlank(domainName) && previousDraft != null) {
            domainName = previousDraft.domainName();
        }
        boolean canReusePrevious = canReusePreviousDraft(previousDraft, domainName);
        String query = !isBlank(task.query()) ? task.query() : canReusePrevious ? previousDraft.query() : task.query();
        String parseIntent = !isBlank(task.intent()) ? task.intent() : canReusePrevious ? "create" : "";
        DomainCapabilityRegistry.DomainCapability domain = registry.findDomain(domainName).orElse(null);
        List<String> missing = new ArrayList<>();
        Map<String, String> params = new HashMap<>();

        if (domain == null || "reject".equals(parseIntent)) {
            missing.add("unsupportedDomain");
            return new SubscriptionDraft(query, domainName, parseIntent, null, params, null, null, null,
                    missing, "지원하지 않는 요청이에요.", task.confidence());
        }

        if (!"create".equals(parseIntent)) {
            missing.add("unsupportedIntent");
            return new SubscriptionDraft(query, domainName, parseIntent, null, params, null, null, null,
                    missing, "알림 수정과 삭제는 아직 채팅 생성 플로우에서 처리하지 않아요.", task.confidence());
        }

        if (domain.status() != DomainCapabilityRegistry.SupportStatus.ENABLED) {
            missing.add("unsupportedCapability");
            return new SubscriptionDraft(query, domainName, null, null, params,
                    explicitCron(task.cronExpr(), userMessage), explicitChannel(userMessage), null, missing,
                    domain.label() + " 알림은 준비 중이에요. 현재는 부동산 아파트 매매 실거래가 알림만 만들 수 있어요.",
                    task.confidence());
        }

        String intent = "apartment_trade_price";
        DomainCapabilityRegistry.IntentCapability capability = registry.requireIntent(domainName, intent);
        params.putAll(capability.defaults());

        String region = extractRegion(query, task.target());
        if (region == null && canReusePrevious) {
            region = previousDraft.monitoringParams().get("region");
        }
        if (region == null) {
            missing.add("region");
        } else {
            params.put("region", region);
        }

        Optional<StructuredCondition> condition = StructuredCondition.parse(task.condition());
        if (condition.isEmpty() && canReusePrevious) {
            condition = StructuredCondition.fromParameters(previousDraft.monitoringParams());
        }
        condition.ifPresent(structuredCondition -> params.putAll(structuredCondition.toParameterMap()));
        if (condition.isEmpty()) {
            missing.add("condition");
        }

        String cronExpr = explicitCron(task.cronExpr(), userMessage);
        if (cronExpr == null && canReusePrevious) {
            cronExpr = previousDraft.cronExpr();
        }
        if (cronExpr == null) {
            missing.add("cadence");
        }

        String channel = explicitChannel(userMessage);
        if (channel == null && canReusePrevious) {
            channel = previousDraft.notificationChannel();
        }
        if (channel == null) {
            missing.add("notificationChannel");
        }

        String targetAddress = canReusePrevious ? previousDraft.notificationTargetAddress() : null;
        return new SubscriptionDraft(query, domainName, intent, capability.toolName(), params,
                cronExpr, channel, targetAddress, missing, assistantQuestion(missing), task.confidence());
    }

    private String canonicalDomainName(String value) {
        return switch (value == null ? "" : value.trim()) {
            case "부동산", "real-estate" -> "real-estate";
            case "법률", "법률/규제", "law-regulation" -> "law-regulation";
            case "채용", "recruitment" -> "recruitment";
            case "경매", "경매/희소매물", "auction" -> "auction";
            default -> value == null ? "" : value.trim();
        };
    }

    private String normalizeCron(String value) {
        return switch (value == null ? "" : value.trim()) {
            case "0 * * * *" -> "0 0 * * * *";
            case "0 9 * * *" -> "0 0 9 * * *";
            case "0 9 * * 1" -> "0 0 9 * * MON";
            case "0 9 * * 1-5" -> "0 0 9 * * MON-FRI";
            default -> value == null || value.isBlank() ? null : value.trim();
        };
    }

    private String explicitCron(String cronExpr, String userMessage) {
        String text = lower(userMessage);
        boolean explicit = text.contains("매시간")
                || text.contains("매일")
                || text.contains("매주")
                || text.contains("평일")
                || text.contains("오전")
                || text.contains("오후")
                || text.contains("아침")
                || text.contains("저녁")
                || text.contains("밤")
                || text.contains("마다")
                || text.contains("체크");
        return explicit ? normalizeCron(cronExpr) : null;
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

    private String extractRegion(String query, String target) {
        Matcher queryMatcher = REGION.matcher(query == null ? "" : query);
        if (queryMatcher.find()) {
            return queryMatcher.group(1);
        }
        Matcher targetMatcher = REGION.matcher(target == null ? "" : target);
        if (targetMatcher.find()) {
            return targetMatcher.group(1);
        }
        return extractAliasRegion(query, target);
    }

    private String extractAliasRegion(String query, String target) {
        String text = (query == null ? "" : query) + " " + (target == null ? "" : target);
        return REGION_ALIASES.stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
    }

    private String assistantQuestion(List<String> missing) {
        if (missing.contains("region")) {
            return "어느 지역의 아파트 매매 실거래가를 확인할까요?";
        }
        if (missing.contains("condition")) {
            return "어떤 가격 변동 조건 시 알림을 받으시겠어요? 예: 5% 이상 상승, 50만원 이상 변동 등";
        }
        if (missing.contains("cadence")) {
            return "얼마나 자주 확인할까요?";
        }
        if (missing.contains("notificationChannel")) {
            return "알림을 받을 채널을 선택해 주세요. Telegram, Discord, Email 중 무엇으로 받을까요?";
        }
        return "";
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean canReusePreviousDraft(SubscriptionDraft previousDraft, String domainName) {
        return previousDraft != null && domainName.equals(previousDraft.domainName());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
