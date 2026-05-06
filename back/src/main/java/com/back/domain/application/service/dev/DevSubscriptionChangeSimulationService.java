package com.back.domain.application.service.dev;

import com.back.domain.application.port.out.GenerateMonitoringBriefingPort;
import com.back.domain.application.port.out.LoadSubscriptionMonitoringConfigPort;
import com.back.domain.application.port.out.LoadSubscriptionPort;
import com.back.domain.application.service.NotificationDeliveryCreationService;
import com.back.domain.application.service.NotificationDispatcherService;
import com.back.domain.application.service.monitoring.MonitoringAlertMessageBuilder;
import com.back.domain.application.service.monitoring.MonitoringBriefingRequest;
import com.back.domain.application.service.monitoring.MonitoringBriefingResult;
import com.back.domain.application.service.monitoring.MonitoringChangeDecision;
import com.back.domain.application.service.monitoring.MonitoringChangeDetector;
import com.back.domain.application.service.subscriptionconversation.StructuredCondition;
import com.back.domain.model.notification.AlertEvent;
import com.back.domain.model.notification.AlertSource;
import com.back.domain.model.notification.NotificationDelivery;
import com.back.domain.model.subscription.Subscription;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("dev")
@RequiredArgsConstructor
public class DevSubscriptionChangeSimulationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() {};
    private static final BigDecimal DEFAULT_PREVIOUS_VALUE = BigDecimal.valueOf(100_000);
    private static final String RECRUITMENT_DOMAIN = "recruitment";
    private static final String TOOL_PUBLIC_JOB = "search_public_job";
    private static final String TOOL_WORKNET_JOB = "search_worknet_job";
    private static final long DEFAULT_RECRUITMENT_COUNT = 10;
    private static final long DEFAULT_RECRUITMENT_ONGOING_COUNT = 3;
    private static final long MAX_RECRUITMENT_POSTING_SAMPLES = 20;

    private final LoadSubscriptionPort loadSubscriptionPort;
    private final LoadSubscriptionMonitoringConfigPort loadMonitoringConfigPort;
    private final MonitoringChangeDetector monitoringChangeDetector;
    private final MonitoringAlertMessageBuilder alertMessageBuilder;
    private final GenerateMonitoringBriefingPort generateMonitoringBriefingPort;
    private final NotificationDeliveryCreationService deliveryCreationService;
    private final NotificationDispatcherService dispatcherService;

    public DevSubscriptionChangeSimulationResult simulate(
            String subscriptionId,
            Long currentUserId,
            LocalDateTime now
    ) {
        Subscription subscription = loadSubscriptionPort.loadActiveByIdAndUserId(subscriptionId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        SubscriptionMonitoringConfig config = loadMonitoringConfigPort.loadBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));
        Map<String, Object> rawParameters = parameters(config);
        Map<String, String> parameters = stringParameters(rawParameters);
        StructuredCondition condition = StructuredCondition.fromParameters(parameters)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST));

        SummaryPair summaries = fakeSummaries(subscription, config, rawParameters, condition);
        MonitoringChangeDecision decision = monitoringChangeDetector.detect(
                summaries.previous(),
                summaries.current(),
                parameters
        );
        if (!decision.triggered()) {
            return new DevSubscriptionChangeSimulationResult(
                    subscriptionId,
                    false,
                    false,
                    0,
                    0,
                    decision.metricKey(),
                    decision.reason()
            );
        }

        String fallbackMessage = alertMessageBuilder.build(
                subscription.query(),
                config.toolName(),
                decision,
                null
        );
        String mcpContent = fakeMcpContent(subscription, config, summaries);
        Optional<MonitoringBriefingResult> generatedBriefing = generateMonitoringBriefingPort.generate(new MonitoringBriefingRequest(
                subscription.query(),
                config.toolName(),
                decision,
                summaries.previous().toString(),
                summaries.current().toString(),
                mcpContent
        ));
        if (generatedBriefing.isPresent() && !generatedBriefing.get().notificationRecommended()) {
            return new DevSubscriptionChangeSimulationResult(
                    subscriptionId,
                    false,
                    true,
                    0,
                    0,
                    decision.metricKey(),
                    "ai notification not recommended"
            );
        }
        String message = generatedBriefing
                .map(MonitoringBriefingResult::message)
                .filter(briefing -> !briefing.isBlank())
                .orElse(fallbackMessage);

        List<NotificationDelivery> deliveries = deliveryCreationService.createFor(alertEvent(
                subscription,
                message,
                decision,
                now
        ));
        int dispatchedCount = dispatcherService.dispatch(deliveries, now);

        return new DevSubscriptionChangeSimulationResult(
                subscriptionId,
                true,
                generatedBriefing.isPresent(),
                deliveries.size(),
                dispatchedCount,
                decision.metricKey(),
                decision.reason()
        );
    }

    private SummaryPair fakeSummaries(
            Subscription subscription,
            SubscriptionMonitoringConfig config,
            Map<String, Object> rawParameters,
            StructuredCondition condition
    ) {
        if (isRecruitmentSimulation(subscription, config, rawParameters)) {
            return fakeRecruitmentSummaries(recruitmentToolName(config, rawParameters), condition);
        }
        return fakeRealEstateSummaries(condition);
    }

    private SummaryPair fakeRealEstateSummaries(StructuredCondition condition) {
        BigDecimal comparable = comparableValue(condition);
        BigDecimal delta = deltaValue(DEFAULT_PREVIOUS_VALUE, comparable, condition.unit());
        BigDecimal previous = previousValue(delta);
        BigDecimal signedDelta = signedDelta(delta, condition.direction());
        BigDecimal current = previous.add(signedDelta);

        return new SummaryPair(realEstateSummaryNode(previous), realEstateSummaryNode(current));
    }

    private SummaryPair fakeRecruitmentSummaries(String toolName, StructuredCondition condition) {
        long delta = recruitmentDeltaValue(condition);
        long signedDelta = signedCountDelta(delta, condition.direction());
        long previousCount = Math.max(DEFAULT_RECRUITMENT_COUNT, delta * 4);
        long previousOngoingCount = Math.min(
                previousCount,
                Math.max(
                        DEFAULT_RECRUITMENT_ONGOING_COUNT,
                        condition.metric() == StructuredCondition.Metric.ONGOING_COUNT
                                && condition.direction() == StructuredCondition.Direction.DOWN
                                ? delta * 4
                                : DEFAULT_RECRUITMENT_ONGOING_COUNT
                )
        );
        long currentCount = previousCount;
        long currentOngoingCount = previousOngoingCount;
        if (condition.metric() == StructuredCondition.Metric.ONGOING_COUNT) {
            currentOngoingCount += signedDelta;
            currentCount = Math.max(currentCount, currentOngoingCount);
        } else {
            currentCount += signedDelta;
            if (signedDelta > 0) {
                currentOngoingCount += signedDelta;
            }
        }
        currentCount = Math.max(1, currentCount);
        currentOngoingCount = Math.max(0, Math.min(currentOngoingCount, currentCount));

        return new SummaryPair(
                recruitmentSummaryNode(toolName, previousCount, previousOngoingCount, 0),
                recruitmentSummaryNode(toolName, currentCount, currentOngoingCount, Math.max(0, signedDelta))
        );
    }

    private boolean isRecruitmentSimulation(
            Subscription subscription,
            SubscriptionMonitoringConfig config,
            Map<String, Object> rawParameters
    ) {
        return isRecruitmentDomain(subscription)
                || isRecruitmentTool(config.toolName())
                || isRecruitmentTool(String.valueOf(rawParameters.get("dataToolName")));
    }

    private boolean isRecruitmentDomain(Subscription subscription) {
        return subscription.domain() != null && RECRUITMENT_DOMAIN.equals(subscription.domain().name());
    }

    private boolean isRecruitmentTool(String toolName) {
        return TOOL_PUBLIC_JOB.equals(toolName) || TOOL_WORKNET_JOB.equals(toolName);
    }

    private String recruitmentToolName(SubscriptionMonitoringConfig config, Map<String, Object> rawParameters) {
        if (isRecruitmentTool(config.toolName())) {
            return config.toolName();
        }
        Object dataToolName = rawParameters.get("dataToolName");
        if (dataToolName != null && isRecruitmentTool(String.valueOf(dataToolName))) {
            return String.valueOf(dataToolName);
        }
        return TOOL_PUBLIC_JOB;
    }

    private BigDecimal comparableValue(StructuredCondition condition) {
        BigDecimal threshold = thresholdForDetector(condition);
        if ((condition.operator() == StructuredCondition.Operator.LT
                || condition.operator() == StructuredCondition.Operator.LTE)
                && threshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        return switch (condition.operator()) {
            case GTE, LTE -> threshold;
            case GT -> threshold.add(step(condition.unit()));
            case LT -> threshold.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        };
    }

    private BigDecimal thresholdForDetector(StructuredCondition condition) {
        if (condition.unit() == StructuredCondition.Unit.EOK) {
            return condition.threshold().multiply(BigDecimal.valueOf(10_000));
        }
        return condition.threshold();
    }

    private BigDecimal step(StructuredCondition.Unit unit) {
        if (unit == StructuredCondition.Unit.PERCENT || unit == StructuredCondition.Unit.COUNT) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(100);
    }

    private BigDecimal deltaValue(
            BigDecimal previous,
            BigDecimal comparable,
            StructuredCondition.Unit unit
    ) {
        if (unit == StructuredCondition.Unit.PERCENT) {
            return previous.multiply(comparable)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                    .abs();
        }
        return comparable.abs();
    }

    private BigDecimal previousValue(BigDecimal delta) {
        BigDecimal minimum = delta.multiply(BigDecimal.valueOf(4));
        return DEFAULT_PREVIOUS_VALUE.max(minimum).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal signedDelta(BigDecimal delta, StructuredCondition.Direction direction) {
        BigDecimal rounded = delta.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) == 0) {
            rounded = BigDecimal.ONE;
        }
        if (direction == StructuredCondition.Direction.DOWN) {
            return rounded.negate();
        }
        return rounded;
    }

    private long recruitmentDeltaValue(StructuredCondition condition) {
        BigDecimal comparable = comparableValue(condition).abs();
        BigDecimal rounded = comparable.setScale(0, RoundingMode.CEILING);
        if (rounded.compareTo(BigDecimal.ZERO) == 0) {
            return 1;
        }
        return rounded.longValue();
    }

    private long signedCountDelta(long delta, StructuredCondition.Direction direction) {
        if (direction == StructuredCondition.Direction.DOWN) {
            return -delta;
        }
        return delta;
    }

    private JsonNode realEstateSummaryNode(BigDecimal value) {
        ObjectNode summary = OBJECT_MAPPER.createObjectNode();
        summary.put("avg_deal_amount", value.setScale(0, RoundingMode.HALF_UP).longValue());
        summary.put("count", 10);
        return summary;
    }

    private JsonNode recruitmentSummaryNode(
            String toolName,
            long count,
            long ongoingCount,
            long newPostingCount
    ) {
        ObjectNode summary = OBJECT_MAPPER.createObjectNode();
        summary.put("count", count);
        summary.put("ongoing_count", ongoingCount);
        ArrayNode postings = summary.putArray("postings");
        long normalizedNewPostingCount = Math.min(Math.min(newPostingCount, count), MAX_RECRUITMENT_POSTING_SAMPLES);
        long existingPostingCount = Math.min(
                count - normalizedNewPostingCount,
                MAX_RECRUITMENT_POSTING_SAMPLES - normalizedNewPostingCount
        );
        summary.put("postings_truncated", count > existingPostingCount + normalizedNewPostingCount);
        long existingOngoingCount = Math.max(0, ongoingCount - normalizedNewPostingCount);
        for (long index = 1; index <= existingPostingCount; index++) {
            postings.add(existingRecruitmentPosting(toolName, index, index <= existingOngoingCount));
        }
        for (long index = 1; index <= normalizedNewPostingCount; index++) {
            postings.add(newRecruitmentPosting(toolName, index));
        }
        return summary;
    }

    private ObjectNode existingRecruitmentPosting(String toolName, long index, boolean ongoing) {
        ObjectNode posting = OBJECT_MAPPER.createObjectNode();
        if (TOOL_WORKNET_JOB.equals(toolName)) {
            String wantedAuthNo = "K12003260428%04d".formatted(index);
            posting.put("wanted_auth_no", wantedAuthNo);
            posting.put("title", "기존 워크넷 백엔드 개발자 " + index);
            posting.put("company", "기존 워크넷 기업 " + index);
            posting.put("region", "서울");
            posting.put("is_ongoing", ongoing);
            posting.put("info_url", "https://work.example/jobs/" + wantedAuthNo);
            return posting;
        }
        long pblntSn = 2026042800L + index;
        posting.put("pblnt_sn", pblntSn);
        posting.put("title", "기존 공공기관 백엔드 개발자 채용 " + index);
        posting.put("institute", "기존 공공기관 " + index);
        posting.putArray("work_regions").add("서울");
        posting.put("is_ongoing", ongoing);
        posting.put("src_url", "https://public.example/jobs/" + pblntSn);
        return posting;
    }

    private ObjectNode newRecruitmentPosting(String toolName, long index) {
        ObjectNode posting = OBJECT_MAPPER.createObjectNode();
        if (TOOL_WORKNET_JOB.equals(toolName)) {
            String wantedAuthNo = "K12003260429%04d".formatted(index);
            posting.put("wanted_auth_no", wantedAuthNo);
            posting.put("title", index == 1 ? "워크넷 백엔드 개발자" : "워크넷 백엔드 개발자 " + index);
            posting.put("company", "워크넷 소프트웨어");
            posting.put("region", "서울");
            posting.put("is_ongoing", true);
            posting.put("info_url", "https://work.example/jobs/" + wantedAuthNo);
            return posting;
        }
        long pblntSn = 2026042900L + index;
        posting.put("pblnt_sn", pblntSn);
        posting.put("title", index == 1
                ? "한국데이터산업진흥원 백엔드 개발자 채용"
                : "공공기관 백엔드 개발자 채용 " + index);
        posting.put("institute", "한국데이터산업진흥원");
        posting.putArray("work_regions").add("서울");
        posting.put("is_ongoing", true);
        posting.put("src_url", "https://public.example/jobs/" + pblntSn);
        return posting;
    }

    private AlertEvent alertEvent(
            Subscription subscription,
            String message,
            MonitoringChangeDecision decision,
            LocalDateTime now
    ) {
        String title = notificationTitle(message);
        String summary = notificationSummary(message);
        return new AlertEvent(
                UuidGenerator.create(),
                subscription,
                title,
                summary,
                "구독 조건에 맞는 변화가 감지되었습니다.",
                List.of(new AlertSource(
                        metricLabel(subscription, decision.metricKey()),
                        null,
                        sourceDescription(decision)
                )),
                now
        );
    }

    private String metricLabel(Subscription subscription, String metricKey) {
        if (isRecruitmentDomain(subscription)) {
            if ("count".equals(metricKey)) {
                return "채용 공고 수 변화";
            }
            if ("ongoing_count".equals(metricKey)) {
                return "진행중 채용 공고 수 변화";
            }
        }
        if ("avg_deal_amount".equals(metricKey)) {
            return "평균 거래금액 변화";
        }
        if ("avg_deposit".equals(metricKey)) {
            return "평균 보증금 변화";
        }
        if ("avg_monthly_rent".equals(metricKey)) {
            return "평균 월세 변화";
        }
        if ("count".equals(metricKey)) {
            return "건수 변화";
        }
        if ("ongoing_count".equals(metricKey)) {
            return "진행중 건수 변화";
        }
        return "구독 지표 변화";
    }

    private String sourceDescription(MonitoringChangeDecision decision) {
        return "이전 %s, 현재 %s, 변화 %s (%s)".formatted(
                formatNumber(decision.previousValue()),
                formatNumber(decision.currentValue()),
                formatNumber(decision.changeValue()),
                formatPercent(decision.changeRate())
        );
    }

    private String formatPercent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private String formatNumber(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String fakeMcpContent(
            Subscription subscription,
            SubscriptionMonitoringConfig config,
            SummaryPair summaries
    ) {
        if (isRecruitmentSimulation(subscription, config, Map.of())) {
            return fakeRecruitmentMcpContent(subscription, summaries);
        }
        return """
                구독 조건에 맞는 변화가 감지되었습니다.
                요청: %s
                이전 요약: %s
                현재 요약: %s
                """.formatted(subscription.query(), summaries.previous(), summaries.current()).trim();
    }

    private String fakeRecruitmentMcpContent(Subscription subscription, SummaryPair summaries) {
        List<PostingLine> publicJobs = briefingPostings(summaries, TOOL_PUBLIC_JOB);
        List<PostingLine> worknetJobs = briefingPostings(summaries, TOOL_WORKNET_JOB);
        StringBuilder content = new StringBuilder();
        content.append("구독 조건에 맞는 채용 변화가 감지되었습니다.\n");
        content.append("요청: ").append(subscription.query());
        appendPostingSection(content, "공공채용", publicJobs);
        appendPostingSection(content, "워크넷", worknetJobs);
        if (publicJobs.isEmpty() && worknetJobs.isEmpty()) {
            content.append("\n신규 공고 상세는 현재 채용 공고 수 변화만 감지되었습니다.");
        }
        return content.toString().trim();
    }

    private void appendPostingSection(StringBuilder content, String label, List<PostingLine> postings) {
        if (postings.isEmpty()) {
            return;
        }
        content.append("\n\n").append(label);
        postings.forEach(posting -> {
            content.append("\n- ").append(posting.title());
            if (!isBlank(posting.url())) {
                content.append("\n  ").append(posting.url());
            }
        });
    }

    private List<PostingLine> briefingPostings(SummaryPair summaries, String source) {
        Set<String> previousIds = postingIdentities(summaries.previous());
        List<PostingLine> postings = new ArrayList<>();
        JsonNode currentPostings = summaries.current().path("postings");
        if (!currentPostings.isArray()) {
            return postings;
        }
        currentPostings.forEach(posting -> {
            String identity = postingIdentity(posting);
            if (identity == null || previousIds.contains(identity) || !identity.startsWith(source + ":")) {
                return;
            }
            postings.add(new PostingLine(postingTitle(posting), postingUrl(posting)));
        });
        return postings;
    }

    private Set<String> postingIdentities(JsonNode summary) {
        Set<String> identities = new HashSet<>();
        JsonNode postings = summary.path("postings");
        if (!postings.isArray()) {
            return identities;
        }
        postings.forEach(posting -> {
            String identity = postingIdentity(posting);
            if (identity != null) {
                identities.add(identity);
            }
        });
        return identities;
    }

    private String postingIdentity(JsonNode posting) {
        JsonNode publicId = posting.path("pblnt_sn");
        if (!publicId.isMissingNode() && !publicId.isNull() && !publicId.asText().isBlank()) {
            return TOOL_PUBLIC_JOB + ":" + publicId.asText();
        }
        JsonNode worknetId = posting.path("wanted_auth_no");
        if (!worknetId.isMissingNode() && !worknetId.isNull() && !worknetId.asText().isBlank()) {
            return TOOL_WORKNET_JOB + ":" + worknetId.asText();
        }
        return null;
    }

    private String postingTitle(JsonNode posting) {
        for (String key : List.of("title", "wantedTitle", "recrutPbancTtl", "recrut_pbanc_ttl")) {
            JsonNode value = posting.path(key);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "(제목 없음)";
    }

    private String postingUrl(JsonNode posting) {
        for (String key : List.of("src_url", "srcUrl", "info_url", "wantedInfoUrl")) {
            JsonNode value = posting.path(key);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String notificationTitle(String message) {
        String title = message == null
                ? ""
                : message.lines()
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElse("");
        title = stripPrefix(title.strip(), "[AI 변화 브리핑]");
        title = stripPrefix(title.strip(), "[변화 감지]");
        return isBlank(title) ? "변화 감지 알림" : title;
    }

    private String notificationSummary(String message) {
        return isBlank(message) ? "구독 조건에 맞는 변화가 감지되었습니다." : message.strip();
    }

    private String stripPrefix(String value, String prefix) {
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length()).strip();
        }
        return value;
    }

    private Map<String, Object> parameters(SubscriptionMonitoringConfig config) {
        if (isBlank(config.parametersJson())) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(config.parametersJson(), PARAMETER_MAP);
        } catch (JsonProcessingException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Map<String, String> stringParameters(Map<String, Object> parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (value != null) {
                values.put(key, String.valueOf(value));
            }
        });
        return values;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SummaryPair(JsonNode previous, JsonNode current) {
    }

    private record PostingLine(String title, String url) {
    }
}
