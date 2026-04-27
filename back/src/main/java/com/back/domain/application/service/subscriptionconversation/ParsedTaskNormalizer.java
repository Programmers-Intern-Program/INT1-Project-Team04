package com.back.domain.application.service.subscriptionconversation;

import com.back.domain.application.result.ParsedTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ParsedTaskNormalizer {

    private static final Pattern REGION = Pattern.compile("([가-힣]+구)");

    private final DomainCapabilityRegistry registry;

    public SubscriptionDraft normalize(ParsedTask task, String userMessage) {
        String domainName = canonicalDomainName(task.domainName());
        DomainCapabilityRegistry.DomainCapability domain = registry.findDomain(domainName).orElse(null);
        List<String> missing = new ArrayList<>();
        Map<String, String> params = new HashMap<>();

        if (domain == null || "reject".equals(task.intent())) {
            missing.add("unsupportedDomain");
            return new SubscriptionDraft(task.query(), domainName, task.intent(), null, params, null, null, null,
                    missing, "지원하지 않는 요청이에요.", task.confidence());
        }

        if (!"create".equals(task.intent())) {
            missing.add("unsupportedIntent");
            return new SubscriptionDraft(task.query(), domainName, task.intent(), null, params, null, null, null,
                    missing, "알림 수정과 삭제는 아직 채팅 생성 플로우에서 처리하지 않아요.", task.confidence());
        }

        if (domain.status() != DomainCapabilityRegistry.SupportStatus.ENABLED) {
            missing.add("unsupportedCapability");
            return new SubscriptionDraft(task.query(), domainName, null, null, params,
                    explicitCron(task.cronExpr(), userMessage), explicitChannel(userMessage), null, missing,
                    domain.label() + " 알림은 준비 중이에요. 현재는 부동산 아파트 매매 실거래가 알림만 만들 수 있어요.",
                    task.confidence());
        }

        String intent = "apartment_trade_price";
        DomainCapabilityRegistry.IntentCapability capability = registry.requireIntent(domainName, intent);
        params.putAll(capability.defaults());

        String region = extractRegion(task.query(), task.target());
        if (region == null) {
            missing.add("region");
        } else {
            params.put("region", region);
        }

        if (!isBlank(task.condition())) {
            params.put("condition", task.condition());
        }

        String cronExpr = explicitCron(task.cronExpr(), userMessage);
        if (cronExpr == null) {
            missing.add("cadence");
        }

        String channel = explicitChannel(userMessage);
        if (channel == null) {
            missing.add("notificationChannel");
        }

        return new SubscriptionDraft(task.query(), domainName, intent, capability.toolName(), params,
                cronExpr, channel, null, missing, assistantQuestion(missing), task.confidence());
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
        return targetMatcher.find() ? targetMatcher.group(1) : null;
    }

    private String assistantQuestion(List<String> missing) {
        if (missing.contains("region")) {
            return "어느 지역의 아파트 매매 실거래가를 확인할까요?";
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
