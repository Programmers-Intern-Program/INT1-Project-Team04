package com.back.domain.adapter.out.persistence.mcp;

import com.back.domain.application.port.out.ExecuteMcpToolPort;
import com.back.domain.application.port.out.FetchInfoDataPort;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.port.out.LoadMcpToolPort;
import com.back.domain.application.result.McpExecutionResult;
import com.back.domain.model.domain.Domain;
import com.back.domain.model.mcp.McpTool;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Map<String, String> DOMAIN_TOOL_MAP = Map.of(
            "부동산", DEFAULT_REAL_ESTATE_TOOL,
            "real-estate", DEFAULT_REAL_ESTATE_TOOL,
            "법률", "search_law_info",
            "law-regulation", "search_law_info",
            "채용", "search_public_job",
            "recruitment", "search_public_job",
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

    public FetchInfoDataAdapter(
            LoadMcpToolPort loadMcpToolPort,
            ExecuteMcpToolPort executeMcpToolPort,
            LoadDomainPort loadDomainPort
    ) {
        this.loadMcpToolPort = loadMcpToolPort;
        this.executeMcpToolPort = executeMcpToolPort;
        this.loadDomainPort = loadDomainPort;
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
        String region = extractRegion(query);
        if (isMolitTool(toolName)) {
            String dealYmd = LocalDateTime.now().minusMonths(1).format(DEAL_YMD_FORMATTER);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("region", isBlank(region) ? "서울" : region);
            input.put("deal_ymd", dealYmd);
            return Map.of("input", input);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        if (!isBlank(region)) {
            input.put("region", region);
        }
        return Map.of("input", input);
    }

    private String buildSummary(String domainName, String content, String query) {
        if (isBlank(content)) {
            return domainName + " 데이터를 조회했지만 결과가 없습니다.";
        }
        return content;
    }

    private String extractRegion(String query) {
        Matcher matcher = REGION.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean isMolitTool(String toolName) {
        return MOLIT_TOOL_PREFIXES.stream().anyMatch(toolName::startsWith);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
