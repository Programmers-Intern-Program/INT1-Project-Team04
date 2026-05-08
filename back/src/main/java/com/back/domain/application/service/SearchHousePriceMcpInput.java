package com.back.domain.application.service;

import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

record SearchHousePriceMcpInput(String region, String dealYmd) {
    private static final Pattern DEAL_YMD = Pattern.compile("^[0-9]{6}$");
    private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    SearchHousePriceMcpInput {
        if (region == null || region.isBlank()) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
        if (dealYmd == null || !DEAL_YMD.matcher(dealYmd).matches()) {
            throw new ApiException(ErrorCode.MCP_REQUEST_FAILED);
        }
    }

    static SearchHousePriceMcpInput from(Map<String, Object> parameters, LocalDateTime now) {
        return new SearchHousePriceMcpInput(
                stringValue(parameters.get("region")),
                dealYmd(parameters, now)
        );
    }

    Map<String, Object> toArguments() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("region", region);
        input.put("deal_ymd", dealYmd);
        return Map.of("input", input);
    }

    private static String dealYmd(Map<String, Object> parameters, LocalDateTime now) {
        String dealYmd = stringValue(parameters.get("deal_ymd"));
        if (!isBlank(dealYmd)) {
            return dealYmd;
        }
        dealYmd = stringValue(parameters.get("dealYmd"));
        if (!isBlank(dealYmd)) {
            return dealYmd;
        }
        return now.minusMonths(1).format(DEAL_YMD_FORMATTER);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
