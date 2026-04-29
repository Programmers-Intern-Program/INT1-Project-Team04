package com.back.domain.application.service.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class MonitoringQueryMatcher {

    public boolean sameTarget(JsonNode previousQuery, JsonNode currentQuery) {
        String previousLawdCd = text(previousQuery, "lawd_cd");
        String currentLawdCd = text(currentQuery, "lawd_cd");
        return !isBlank(previousLawdCd) && previousLawdCd.equals(currentLawdCd);
    }

    private String text(JsonNode node, String key) {
        if (node == null || node.path(key).isMissingNode() || node.path(key).isNull()) {
            return null;
        }
        return node.path(key).asText();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
