package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.FetchInfoDataPort;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FetchInfoDataAdapter implements FetchInfoDataPort {

    private static final Pattern REGION = Pattern.compile(
            "([가-힣]+(?:특별자치시|특별자치도|특별시|광역시|시|군|구)|서울|부산|대구|인천|광주|대전|울산|세종|제주)"
    );
    private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final List<String> MOLIT_TOOL_PREFIXES = List.of(
            "search_house_price", "search_apt_rent", "search_offi_trade",
            "search_offi_rent", "search_rh_trade", "search_rh_rent"
    );
    private static final String DEFAULT_REAL_ESTATE_TOOL = "search_house_price";
    private static final String DEFAULT_RECRUITMENT_TOOL = "search_public_job";

    private static final List<String> RECRUITMENT_STOPWORDS = List.of(
            "채용", "공고", "구인", "일자리", "워크넷", "공공기관", "공기업", "기관",
            "알려줘", "검색", "조회", "확인", "어때", "어떻게", "뭐", "뭐야", "많아",
            "많은지", "현황", "목록", "요즘", "지금", "현재", "있어", "없어",
            "검색해줘", "알려줘", "보여줘", "찾아줘", "전체", "뭐", "있나요", "없나요"
    );

    private static final Map<String, String> DOMAIN_TOOL_MAP = Map.of(
            "부동산", DEFAULT_REAL_ESTATE_TOOL,
            "real-estate", DEFAULT_REAL_ESTATE_TOOL,
            "법률", "search_law_info",
            "law-regulation", "search_law_info",
            "채용", DEFAULT_RECRUITMENT_TOOL,
            "recruitment", DEFAULT_RECRUITMENT_TOOL,
            "경매", "search_g2b_bid",
            "auction", "search_g2b_bid"
    );

    private static final Map<String, String> DOMAIN_DB_NAME_MAP = Map.of(
            "부동산", "real-estate",
            "real-estate", "real-estate",
            "법률", "law-regulation",
            "law-regulation", "law-regulation",
            "채용", "recruitment",
            "recruitment", "recruitment",
            "경매", "auction",
            "auction", "auction"
    );

    private final LoadMcpToolPort loadMcpToolPort;
    private final ExecuteMcpToolPort executeMcpToolPort;
    private final LoadDomainPort loadDomainPort;
    private final ObjectMapper objectMapper;

    public FetchInfoDataAdapter(
            LoadMcpToolPort loadMcpToolPort,
            ExecuteMcpToolPort executeMcpToolPort,
            LoadDomainPort loadDomainPort,
            ObjectMapper objectMapper
    ) {
        this.loadMcpToolPort = loadMcpToolPort;
        this.executeMcpToolPort = executeMcpToolPort;
        this.loadDomainPort = loadDomainPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<InfoDataResult> fetch(String domainName, String query) {
        String toolName = DOMAIN_TOOL_MAP.get(domainName);
        if (toolName == null) {
            return Optional.empty();
        }

        String dbName = DOMAIN_DB_NAME_MAP.get(domainName);
        Domain domain = loadDomainPort.loadAll().stream()
                .filter(d -> dbName.equals(d.name()))
                .findFirst()
                .orElse(null);
        if (domain == null) {
            return Optional.empty();
        }

        McpTool tool = loadMcpToolPort.loadByDomainIdAndName(domain.id(), toolName)
                .or(() -> loadMcpToolPort.loadByDomainId(domain.id()))
                .orElse(null);
        if (tool == null) {
            return Optional.empty();
        }

        try {
            Map<String, Object> arguments = buildArguments(tool.name(), query);
            McpExecutionResult result = executeMcpToolPort.execute(tool, arguments);
            String summary = buildSummary(domainName, result.content(), query);
            return Optional.of(new InfoDataResult(summary, result.content()));
        } catch (Exception e) {
            log.warn("[FetchInfoDataAdapter] 데이터 조회 실패 - domain={}, query={}", domainName, query, e);
            return Optional.empty();
        }
    }

    private Map<String, Object> buildArguments(String toolName, String query) {
        if (isMolitTool(toolName)) {
            String region = extractRegion(query);
            String dealYmd = LocalDateTime.now().minusMonths(1).format(DEAL_YMD_FORMATTER);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("region", isBlank(region) ? "서울" : region);
            input.put("deal_ymd", dealYmd);
            return Map.of("input", input);
        }
        if (isRecruitmentTool(toolName)) {
            String keyword = extractRecruitmentKeyword(query);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("page_no", 1);
            input.put("num_of_rows", 20);
            input.put("ongoing_yn", "Y");
            if (!isBlank(keyword)) {
                input.put("recrut_pbanc_ttl", keyword);
            }
            return Map.of("input", input);
        }
        if (isLawTool(toolName)) {
            Map<String, Object> input = new LinkedHashMap<>();
            return Map.of("input", input);
        }
        if (isAuctionTool(toolName)) {
            Map<String, Object> input = new LinkedHashMap<>();
            return Map.of("input", input);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        String region = extractRegion(query);
        if (!isBlank(region)) {
            input.put("region", region);
        }
        return Map.of("input", input);
    }

    private boolean isLawTool(String toolName) {
        return "search_law_info".equals(toolName) || "search_bill_info".equals(toolName);
    }

    private boolean isAuctionTool(String toolName) {
        return "search_g2b_bid".equals(toolName);
    }

    private boolean isRecruitmentTool(String toolName) {
        return DEFAULT_RECRUITMENT_TOOL.equals(toolName) || "search_worknet_job".equals(toolName);
    }

    private String extractRecruitmentKeyword(String query) {
        if (isBlank(query)) return null;
        String cleaned = query.trim();
        for (String stop : RECRUITMENT_STOPWORDS) {
            cleaned = cleaned.replace(stop, "");
        }
        cleaned = cleaned.replaceAll("\\s+", " ").strip();
        cleaned = removeRecruitmentDescriptorSuffix(cleaned);
        return isBlank(cleaned) ? null : cleaned;
    }

    private String removeRecruitmentDescriptorSuffix(String keyword) {
        if (isBlank(keyword)) {
            return "";
        }
        String normalized = keyword.replaceAll("\\s+", " ").strip();
        for (String suffix : List.of(" 직무", " 직군")) {
            if (normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length()).strip();
            }
        }
        if (normalized.endsWith("직군") && normalized.length() > "직군".length()) {
            String stem = normalized.substring(0, normalized.length() - "직군".length()).strip();
            if (stem.length() <= 2) {
                return normalized.substring(0, normalized.length() - "군".length()).strip();
            }
            return stem;
        }
        return normalized;
    }

    private String buildSummary(String domainName, String content, String query) {
        if (isBlank(content)) {
            return domainName + " 데이터를 조회했지만 결과가 없습니다.";
        }
        if (isRecruitmentDomain(domainName)) {
            return buildRecruitmentSummary(content, query);
        }
        return content;
    }

    private boolean isRecruitmentDomain(String domainName) {
        return "채용".equals(domainName) || "recruitment".equals(domainName);
    }

    @SuppressWarnings("unchecked")
    private String buildRecruitmentSummary(String content, String query) {
        try {
            Map<String, Object> response = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> structured = (Map<String, Object>) response.get("structured");
            if (structured == null) return content;

            Map<String, Object> summary = (Map<String, Object>) structured.get("summary");
            List<Map<String, Object>> postings = (List<Map<String, Object>>) structured.get("postings");

            int count = summary != null ? ((Number) summary.getOrDefault("count", 0)).intValue() : 0;
            int ongoingCount = summary != null ? ((Number) summary.getOrDefault("ongoing_count", 0)).intValue() : 0;

            String keyword = extractRecruitmentKeyword(query);
            StringBuilder sb = new StringBuilder();

            if (isBlank(keyword)) {
                sb.append("현재 공공기관 진행 중인 채용공시 현황입니다.\n");
                sb.append("총 ").append(count).append("건");
                if (ongoingCount > 0) {
                    sb.append(" (진행 중 ").append(ongoingCount).append("건)");
                }
                sb.append("이 조회되었습니다.");
            } else {
                sb.append("'").append(keyword).append("' 관련 공공기관 채용공시 ");
                sb.append(count).append("건");
                if (ongoingCount > 0) {
                    sb.append(" (진행 중 ").append(ongoingCount).append("건)");
                }
                sb.append("이 조회되었습니다.");
            }

            if (postings != null && !postings.isEmpty()) {
                sb.append("\n\n**주요 채용 공고:**\n");
                List<Map<String, Object>> top = postings.stream().limit(5).collect(Collectors.toList());
                for (int i = 0; i < top.size(); i++) {
                    Map<String, Object> p = top.get(i);
                    String title = str(p.getOrDefault("title", "-"));
                    String institute = str(p.getOrDefault("institute", ""));
                    String endDate = str(p.getOrDefault("pbanc_end_date", ""));
                    sb.append(i + 1).append(". ").append(title);
                    if (!isBlank(institute) && !"-".equals(institute) && !"null".equals(institute)) {
                        sb.append(" — ").append(institute);
                    }
                    if (!isBlank(endDate) && !"null".equals(endDate) && !"-".equals(endDate)) {
                        sb.append(" (마감: ").append(endDate).append(")");
                    }
                    sb.append("\n");
                }
                if (postings.size() > 5) {
                    sb.append("... 외 ").append(postings.size() - 5).append("건\n");
                }
            } else if (count == 0) {
                if (!isBlank(keyword)) {
                    sb.append("\n'").append(keyword).append("' 관련 공고가 현재 없습니다. ");
                    sb.append("다른 키워드로 검색해보시겠어요?");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[FetchInfoDataAdapter] 채용 요약 포맷 실패 - 원본 반환", e);
            return content;
        }
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String extractRegion(String query) {
        // 입력 예: "성남시 분당구 아파트 매매 …" → "성남시 분당구" (행정구 포함 다중 토큰)
        // REGION 정규식은 "OO시/군/구" 단일 토큰만 잡으므로 모든 매치를 모은 뒤
        // 공백으로만 인접한 매치들을 하나의 region 묶음으로 결합해 최장 묶음을 반환한다.
        if (query == null || query.isBlank()) {
            return null;
        }
        Matcher matcher = REGION.matcher(query);
        List<int[]> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(new int[]{matcher.start(), matcher.end()});
        }
        if (matches.isEmpty()) {
            return null;
        }
        int bestStart = matches.get(0)[0];
        int bestEnd = matches.get(0)[1];
        int bestCount = 1;
        int curStart = bestStart;
        int curEnd = bestEnd;
        int curCount = 1;
        for (int i = 1; i < matches.size(); i++) {
            int s = matches.get(i)[0];
            int e = matches.get(i)[1];
            if (query.substring(curEnd, s).chars().allMatch(Character::isWhitespace)) {
                curEnd = e;
                curCount++;
            } else {
                curStart = s;
                curEnd = e;
                curCount = 1;
            }
            if (curCount > bestCount) {
                bestStart = curStart;
                bestEnd = curEnd;
                bestCount = curCount;
            }
        }
        return query.substring(bestStart, bestEnd).replaceAll("\\s+", " ").strip();
    }

    private boolean isMolitTool(String toolName) {
        return MOLIT_TOOL_PREFIXES.stream().anyMatch(toolName::startsWith);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
