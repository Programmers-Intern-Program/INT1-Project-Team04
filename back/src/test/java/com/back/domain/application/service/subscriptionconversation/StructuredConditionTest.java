package com.back.domain.application.service.subscriptionconversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Application: structured monitoring condition")
class StructuredConditionTest {

    @Test
    @DisplayName("parses percent rise condition into canonical fields")
    void parsesPercentRiseCondition() {
        Optional<StructuredCondition> parsed = StructuredCondition.parse("5% 이상 상승");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().toParameterMap())
                .containsEntry("conditionMetric", "AVG_PRICE")
                .containsEntry("conditionDirection", "UP")
                .containsEntry("conditionOperator", "GTE")
                .containsEntry("conditionThreshold", "5")
                .containsEntry("conditionUnit", "PERCENT");
    }

    @Test
    @DisplayName("rejects vague condition without numeric threshold")
    void rejectsVagueCondition() {
        assertThat(StructuredCondition.parse("오르면 알려줘")).isEmpty();
    }
}
