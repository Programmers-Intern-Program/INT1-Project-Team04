package com.back.domain.adapter.in.web.parse;

import com.back.domain.application.result.ParsedTask;
import java.util.List;

public record ParsedTaskDto(
        String intent,
        String domainName,
        String query,
        String condition,
        String cronExpr,
        String channel,
        String apiType,
        String target,
        List<String> urls,
        double confidence,
        boolean needsConfirmation,
        String confirmationQuestion
) {
    public static ParsedTaskDto from(ParsedTask task) {
        return new ParsedTaskDto(
                task.intent(),
                task.domainName(),
                task.query(),
                task.condition(),
                task.cronExpr(),
                task.channel(),
                task.apiType(),
                task.target(),
                task.urls(),
                task.confidence(),
                task.needsConfirmation(),
                task.confirmationQuestion()
        );
    }
}
