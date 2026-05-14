package com.back.domain.adapter.out.ai;

import com.back.domain.adapter.out.ai.McpToolExecutionRecorder.Execution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.StreamSupport;

final class NotificationBriefingPayloadFactory {

    private static final String BRIEFING_CONTRACT_VERSION = "channel-v1";
    private static final DecimalFormat RATE_FORMAT = new DecimalFormat("0.##");

    private NotificationBriefingPayloadFactory() {
    }

    static String enrichSendNotificationInput(
            String rawInput,
            List<Execution> executions,
            ObjectMapper objectMapper
    ) {
        try {
            JsonNode root = objectMapper.readTree(rawInput);
            if (!root.isObject()) {
                return rawInput;
            }
            ObjectNode request = requestNode((ObjectNode) root);
            Optional<CompareEvidence> compare = compareEvidence(request, executions, objectMapper);
            if (compare.isEmpty() || !compare.get().isNotificationRequired()) {
                return rawInput;
            }

            DataEvidence data = dataEvidence(executions, objectMapper).orElse(DataEvidence.empty());
            ObjectNode metadata = objectNode(request, "metadata", objectMapper);
            metadata.put("briefingContractVersion", BRIEFING_CONTRACT_VERSION);
            ObjectNode briefing = compare.get().isRecruitment()
                    ? recruitmentBriefing(request, compare.get(), objectMapper)
                    : realEstateBriefing(request, compare.get(), data, objectMapper);
            metadata.set("briefing", briefing);
            request.set("metadata", metadata);
            if (blank(text(request, "title"))) {
                request.put("title", text(briefing, "title"));
            }
            if (blank(text(request, "message"))) {
                request.put("message", text(briefing, "summary"));
            }
            return objectMapper.writeValueAsString(root);
        } catch (RuntimeException | JsonProcessingException ignored) {
            return rawInput;
        }
    }

    private static ObjectNode requestNode(ObjectNode root) {
        JsonNode wrapped = root.get("input");
        return wrapped != null && wrapped.isObject() ? (ObjectNode) wrapped : root;
    }

    private static Optional<CompareEvidence> compareEvidence(
            ObjectNode request,
            List<Execution> executions,
            ObjectMapper objectMapper
    ) {
        String requestedSubscriptionId = text(request, "subscriptionId", "subscription_id");
        for (int index = executions.size() - 1; index >= 0; index--) {
            Execution execution = executions.get(index);
            if (execution.failed() || !isTool(execution, "compare_subscription_change")) {
                continue;
            }
            Optional<JsonNode> structured = structuredNode(execution.output(), objectMapper);
            if (structured.isEmpty()) {
                continue;
            }
            String subscriptionId = text(structured.get(), "subscriptionId", "subscription_id");
            if (!blank(requestedSubscriptionId) && !requestedSubscriptionId.equals(subscriptionId)) {
                continue;
            }
            JsonNode input = toolPayloadNode(execution.input(), objectMapper).orElse(null);
            return Optional.of(new CompareEvidence(structured.get(), input));
        }
        return Optional.empty();
    }

    private static Optional<DataEvidence> dataEvidence(List<Execution> executions, ObjectMapper objectMapper) {
        for (int index = executions.size() - 1; index >= 0; index--) {
            Execution execution = executions.get(index);
            if (execution.failed() || !isDataTool(execution)) {
                continue;
            }
            Optional<JsonNode> structured = structuredNode(execution.output(), objectMapper);
            if (structured.isEmpty()) {
                continue;
            }
            JsonNode node = structured.get();
            return Optional.of(new DataEvidence(
                    text(node.path("query"), "region"),
                    text(node.path("query"), "deal_ymd", "dealYmd", "dealPeriod"),
                    toolNameFromOutput(node, execution.toolName())
            ));
        }
        return Optional.empty();
    }

    private static ObjectNode realEstateBriefing(
            ObjectNode request,
            CompareEvidence compare,
            DataEvidence data,
            ObjectMapper objectMapper
    ) {
        JsonNode diff = compare.primaryRealEstateDiff();
        String field = text(diff, "field");
        String region = firstNonBlank(
                text(compare.inputParams(), "region"),
                data.region(),
                text(request.path("metadata").path("briefing").path("watchInfo"), "region")
        );
        String dealPeriod = firstNonBlank(
                text(compare.inputParams(), "deal_ymd", "dealYmd", "dealPeriod"),
                data.dealPeriod(),
                text(request.path("metadata").path("briefing").path("watchInfo"), "dealPeriod", "deal_ymd")
        );
        String dataSource = dataSource(data.toolName(), field);
        String metricLabel = metricLabel(field);
        String previous = moneyManwon(text(diff, "baseline_value", "baselineValue"));
        String current = moneyManwon(text(diff, "current_value", "currentValue"));
        String rate = signedRate(diff);
        String previousCount = countText(compare.baselineSummary());
        String currentCount = countText(compare.currentSummary());
        String count = previousCount.equals(currentCount)
                ? currentCount
                : "%s → %s".formatted(previousCount, currentCount);
        String title = firstNonBlank(
                text(request.path("metadata").path("briefing"), "title"),
                text(request, "title"),
                "%s %s 변동 알림".formatted(regionOrDefault(region), metricLabel)
        );
        String summary = firstNonBlank(
                text(request.path("metadata").path("briefing"), "summary"),
                text(request, "message"),
                "%s %s가 %s에서 %s로 %s 변동했습니다.".formatted(
                        regionOrDefault(region),
                        metricLabel,
                        previous,
                        current,
                        rate
                )
        );
        String interpretation = firstNonBlank(
                text(request.path("metadata").path("briefing"), "interpretation"),
                "거래 건수와 표본 구성을 함께 확인한 뒤 시장 변동성을 판단하세요."
        );

        ObjectNode briefing = objectMapper.createObjectNode();
        briefing.put("domain", "real-estate");
        briefing.put("title", title);
        briefing.put("summary", summary);
        ArrayNode changes = objectMapper.createArrayNode();
        changes.add(change(objectMapper, metricLabel, "%s → %s (%s)".formatted(previous, current, rate), previous, current));
        changes.add(change(objectMapper, "변화율", rate, null, null));
        changes.add(change(objectMapper, "거래 건수", count, previousCount, currentCount));
        changes.add(change(objectMapper, "데이터 출처", dataSource, null, null));
        briefing.set("changes", changes);

        ObjectNode watchInfo = objectMapper.createObjectNode();
        watchInfo.put("target", firstNonBlank(text(compare.input(), "query"), text(request.path("metadata").path("briefing").path("watchInfo"), "target"), title));
        watchInfo.put("condition", conditionText(compare.inputParams()));
        if (!blank(region)) {
            watchInfo.put("region", region);
        }
        if (!blank(dealPeriod)) {
            watchInfo.put("dealPeriod", dealPeriod);
        }
        watchInfo.put("dataSource", dataSource);
        briefing.set("watchInfo", watchInfo);

        ArrayNode sources = objectMapper.createArrayNode();
        ObjectNode source = objectMapper.createObjectNode();
        source.put("label", dataSource);
        source.put("description", "공공 실거래 데이터");
        sources.add(source);
        briefing.set("sources", sources);
        briefing.put("interpretation", interpretation);
        return briefing;
    }

    private static ObjectNode recruitmentBriefing(
            ObjectNode request,
            CompareEvidence compare,
            ObjectMapper objectMapper
    ) {
        ArrayNode sources = recruitmentSources(compare.structured(), objectMapper);
        String keyword = firstNonBlank(
                text(compare.inputParams(), "keyword", "recrut_pbanc_ttl"),
                text(request.path("metadata").path("briefing").path("watchInfo"), "keyword")
        );
        String dataSource = recruitmentDataSource(sources);
        String title = firstNonBlank(
                text(request.path("metadata").path("briefing"), "title"),
                text(request, "title"),
                "%s 채용 새 공고".formatted(blank(keyword) ? "공공기관" : keyword)
        );
        String summary = firstNonBlank(
                text(request.path("metadata").path("briefing"), "summary"),
                text(request, "message"),
                recruitmentSummary(compare.structured(), keyword)
        );
        String interpretation = firstNonBlank(
                text(request.path("metadata").path("briefing"), "interpretation"),
                "공고별 마감일, 지원 자격, 중복 공고 여부를 확인하세요."
        );

        ObjectNode briefing = objectMapper.createObjectNode();
        briefing.put("domain", "recruitment");
        briefing.put("title", title);
        briefing.put("summary", summary);
        briefing.set("changes", recruitmentChanges(compare.structured(), dataSource, objectMapper));

        ObjectNode watchInfo = objectMapper.createObjectNode();
        watchInfo.put("target", firstNonBlank(text(compare.input(), "query"), text(request.path("metadata").path("briefing").path("watchInfo"), "target"), title));
        watchInfo.put("condition", conditionText(compare.inputParams()));
        if (!blank(keyword)) {
            watchInfo.put("keyword", keyword);
        }
        watchInfo.put("dataSource", dataSource);
        briefing.set("watchInfo", watchInfo);
        briefing.set("sources", sources);
        briefing.put("interpretation", interpretation);
        return briefing;
    }

    private static ObjectNode change(
            ObjectMapper objectMapper,
            String label,
            String value,
            String previous,
            String current
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("label", label);
        node.put("value", value);
        if (blank(previous)) {
            node.putNull("previous");
        } else {
            node.put("previous", previous);
        }
        if (blank(current)) {
            node.putNull("current");
        } else {
            node.put("current", current);
        }
        return node;
    }

    private static ArrayNode recruitmentChanges(
            JsonNode structured,
            String dataSource,
            ObjectMapper objectMapper
    ) {
        ArrayNode changes = objectMapper.createArrayNode();
        JsonNode diffs = structured.path("diffs");
        if (diffs.isArray()) {
            for (JsonNode diff : diffs) {
                String label = recruitmentMetricLabel(text(diff, "field"));
                String previous = countValue(text(diff, "baseline_value", "baselineValue"));
                String current = countValue(text(diff, "current_value", "currentValue"));
                String value = previous.equals(current) ? current : "%s → %s".formatted(previous, current);
                String rate = signedRate(diff);
                if (!"변화율 확인 필요".equals(rate)) {
                    value = "%s (%s)".formatted(value, rate);
                }
                changes.add(change(objectMapper, label, value, previous, current));
            }
        }
        changes.add(change(objectMapper, "데이터 출처", dataSource, null, null));
        return changes;
    }

    private static ArrayNode recruitmentSources(JsonNode structured, ObjectMapper objectMapper) {
        ArrayNode sources = objectMapper.createArrayNode();
        JsonNode bySource = structured.path("briefing_postings_by_source");
        if (!bySource.isObject()) {
            bySource = structured.path("briefingPostingsBySource");
        }
        addRecruitmentSources(sources, bySource.path("public_job"), objectMapper);
        addRecruitmentSources(sources, bySource.path("worknet_job"), objectMapper);
        return sources;
    }

    private static void addRecruitmentSources(ArrayNode sources, JsonNode postings, ObjectMapper objectMapper) {
        if (!postings.isArray()) {
            return;
        }
        for (JsonNode posting : postings) {
            String title = text(posting, "title", "label");
            String url = text(posting, "url", "src_url", "srcUrl", "info_url", "wantedInfoUrl");
            if (blank(title) || blank(url)) {
                continue;
            }
            ObjectNode source = objectMapper.createObjectNode();
            source.put("label", title);
            source.put("url", url);
            sources.add(source);
        }
    }

    private static Optional<JsonNode> structuredNode(String content, ObjectMapper objectMapper) {
        return readJson(content, objectMapper).flatMap(NotificationBriefingPayloadFactory::findStructuredNode);
    }

    private static Optional<JsonNode> toolPayloadNode(String content, ObjectMapper objectMapper) {
        return readJson(content, objectMapper)
                .map(node -> node.path("input").isObject() ? node.path("input") : node);
    }

    private static Optional<JsonNode> readJson(String content, ObjectMapper objectMapper) {
        if (blank(content)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static Optional<JsonNode> findStructuredNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            JsonNode structured = node.get("structured");
            if (structured != null && structured.isObject()) {
                return Optional.of(structured);
            }
            if (node.has("baseline_initialized") || node.has("condition_satisfied")) {
                return Optional.of(node);
            }
            Iterator<JsonNode> elements = node.elements();
            Iterable<JsonNode> iterable = () -> elements;
            return StreamSupport.stream(iterable.spliterator(), false)
                    .map(NotificationBriefingPayloadFactory::findStructuredNode)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                    .map(NotificationBriefingPayloadFactory::findStructuredNode)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        return Optional.empty();
    }

    private static ObjectNode objectNode(ObjectNode parent, String field, ObjectMapper objectMapper) {
        JsonNode value = parent.get(field);
        if (value != null && value.isObject()) {
            return (ObjectNode) value;
        }
        ObjectNode created = objectMapper.createObjectNode();
        parent.set(field, created);
        return created;
    }

    private static String conditionText(JsonNode params) {
        String threshold = text(params, "conditionThreshold");
        String unit = text(params, "conditionUnit");
        String direction = text(params, "conditionDirection");
        if (blank(threshold)) {
            return "구독 조건 충족";
        }
        String directionText = switch (String.valueOf(direction)) {
            case "UP" -> "상승";
            case "DOWN" -> "하락";
            default -> "변화";
        };
        String unitText = "PERCENT".equals(unit) ? "%" : "";
        return "%s%s 이상 %s".formatted(threshold, unitText, directionText);
    }

    private static String metricLabel(String field) {
        return switch (String.valueOf(field)) {
            case "avg_deposit" -> "평균 보증금";
            case "avg_monthly_rent" -> "평균 월세";
            default -> "평균 매매가";
        };
    }

    private static String dataSource(String toolName, String field) {
        if ("search_apt_rent".equals(toolName)) {
            return "국토교통부 아파트 전월세 실거래가";
        }
        if ("search_offi_trade".equals(toolName)) {
            return "국토교통부 오피스텔 매매 실거래가";
        }
        if ("search_offi_rent".equals(toolName)) {
            return "국토교통부 오피스텔 전월세 실거래가";
        }
        if ("search_rh_trade".equals(toolName)) {
            return "국토교통부 연립다세대 매매 실거래가";
        }
        if ("search_rh_rent".equals(toolName)) {
            return "국토교통부 연립다세대 전월세 실거래가";
        }
        if ("avg_deposit".equals(field) || "avg_monthly_rent".equals(field)) {
            return "국토교통부 전월세 실거래가";
        }
        return "국토교통부 아파트 매매 실거래가";
    }

    private static String recruitmentDataSource(ArrayNode sources) {
        if (sources.isEmpty()) {
            return "공공채용";
        }
        boolean hasWorknet = false;
        for (JsonNode source : sources) {
            String label = text(source, "label");
            if (label != null && label.toLowerCase(Locale.ROOT).contains("worknet")) {
                hasWorknet = true;
                break;
            }
        }
        return hasWorknet ? "공공채용, 워크넷" : "공공채용";
    }

    private static String recruitmentSummary(JsonNode structured, String keyword) {
        String currentCount = countText(structured.path("current_summary").isObject()
                ? structured.path("current_summary")
                : structured.path("currentSummary"));
        return "%s 채용 공고 변화가 감지되었습니다. 현재 공고 수는 %s입니다."
                .formatted(blank(keyword) ? "공공기관" : keyword, currentCount);
    }

    private static String recruitmentMetricLabel(String field) {
        return switch (String.valueOf(field)) {
            case "added_count" -> "신규 공고 수";
            case "removed_count" -> "제외 공고 수";
            case "ongoing_added_count" -> "신규 진행중 공고 수";
            case "ongoing_removed_count" -> "제외된 진행중 공고 수";
            case "ongoing_count" -> "진행중 공고 수";
            case "count" -> "전체 공고 수";
            default -> field;
        };
    }

    private static String countValue(String raw) {
        if (blank(raw)) {
            return "확인 필요";
        }
        try {
            return String.format(Locale.ROOT, "%,d건", Math.round(Double.parseDouble(raw)));
        } catch (NumberFormatException e) {
            return raw.endsWith("건") ? raw : raw + "건";
        }
    }

    private static String toolNameFromOutput(JsonNode structured, String fallback) {
        return firstNonBlank(
                text(structured.path("metadata"), "tool_name", "toolName"),
                fallback
        );
    }

    private static String countText(JsonNode currentSummary) {
        String count = text(currentSummary, "count");
        if (blank(count)) {
            return "확인 필요";
        }
        try {
            return String.format(Locale.ROOT, "%,d건", Math.round(Double.parseDouble(count)));
        } catch (NumberFormatException e) {
            return count.endsWith("건") ? count : count + "건";
        }
    }

    private static String signedRate(JsonNode diff) {
        String raw = text(diff, "change_rate", "changeRate");
        if (blank(raw)) {
            return "변화율 확인 필요";
        }
        try {
            double value = Double.parseDouble(raw);
            String sign = value > 0 ? "+" : "";
            return sign + RATE_FORMAT.format(value) + "%";
        } catch (NumberFormatException e) {
            return raw.contains("%") ? raw : raw + "%";
        }
    }

    private static String moneyManwon(String raw) {
        if (blank(raw)) {
            return "확인 필요";
        }
        try {
            long value = Math.round(Double.parseDouble(raw));
            if (value < 10_000) {
                return String.format(Locale.ROOT, "%,d만원", value);
            }
            long eok = value / 10_000;
            long manwon = value % 10_000;
            if (manwon == 0) {
                return String.format(Locale.ROOT, "%,d억원", eok);
            }
            return String.format(Locale.ROOT, "%,d억 %,d만원", eok, manwon);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static boolean isTool(Execution execution, String toolName) {
        String name = execution.toolName();
        return name != null && (name.equals(toolName) || name.endsWith("_" + toolName));
    }

    private static boolean isDataTool(Execution execution) {
        String name = execution.toolName();
        if (name == null) {
            return false;
        }
        return name.contains("search_house_price")
                || name.contains("search_apt_rent")
                || name.contains("search_offi_trade")
                || name.contains("search_offi_rent")
                || name.contains("search_rh_trade")
                || name.contains("search_rh_rent")
                || name.contains("get_cached_data");
    }

    private static boolean bool(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asBoolean(false);
            }
        }
        return false;
    }

    private static String text(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);
                if (!blank(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String regionOrDefault(String region) {
        return blank(region) ? "부동산" : region;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record CompareEvidence(JsonNode structured, JsonNode input) {

        boolean isNotificationRequired() {
            return isCommonNotificationRequired()
                    && (isRecruitment() || (isRealEstate() && primaryRealEstateDiff().isObject()));
        }

        private boolean isCommonNotificationRequired() {
            return !bool(structured, "baseline_initialized", "baselineInitialized")
                    && bool(structured, "changed")
                    && bool(structured, "condition_satisfied", "conditionSatisfied")
                    && bool(structured, "requires_ai_analysis", "requiresAiAnalysis");
        }

        boolean isRealEstate() {
            String domain = text(structured, "domain");
            return domain != null
                    && (domain.equals("real-estate") || domain.equals("real_estate") || domain.equals("부동산"));
        }

        boolean isRecruitment() {
            String domain = text(structured, "domain");
            return domain != null
                    && (domain.equals("recruitment") || domain.equals("job") || domain.equals("jobs") || domain.equals("채용"));
        }

        JsonNode primaryRealEstateDiff() {
            JsonNode diffs = structured.path("diffs");
            if (!diffs.isArray()) {
                return MissingNode.getInstance();
            }
            for (JsonNode diff : diffs) {
                String field = text(diff, "field");
                if ("avg_deal_amount".equals(field)
                        || "avg_deposit".equals(field)
                        || "avg_monthly_rent".equals(field)) {
                    return diff;
                }
            }
            return MissingNode.getInstance();
        }

        JsonNode inputParams() {
            return input == null ? MissingNode.getInstance() : input.path("params");
        }

        JsonNode currentSummary() {
            return structured.path("current_summary").isObject()
                    ? structured.path("current_summary")
                    : structured.path("currentSummary");
        }

        JsonNode baselineSummary() {
            return structured.path("baseline_summary").isObject()
                    ? structured.path("baseline_summary")
                    : structured.path("baselineSummary");
        }
    }

    private record DataEvidence(String region, String dealPeriod, String toolName) {

        static DataEvidence empty() {
            return new DataEvidence(null, null, null);
        }
    }
}
