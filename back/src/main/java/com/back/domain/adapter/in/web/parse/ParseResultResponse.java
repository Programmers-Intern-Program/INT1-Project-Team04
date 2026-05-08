package com.back.domain.adapter.in.web.parse;

import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import java.util.List;

public record ParseResultResponse(
        String sessionId,
        List<ParsedTaskDto> tasks,
        boolean isComplete,
        String nextQuestion
) {
    public static ParseResultResponse from(ParseResult result) {
        List<ParsedTaskDto> taskDtos = result.tasks().stream()
                .map(ParsedTaskDto::from)
                .toList();

        boolean allComplete = result.tasks().stream()
                .allMatch(t -> !t.needsConfirmation());

        String nextQuestion = result.tasks().stream()
                .filter(ParsedTask::needsConfirmation)
                .map(ParsedTask::confirmationQuestion)
                .filter(q -> !q.isBlank())
                .findFirst()
                .orElse(null);

        return new ParseResultResponse(
                result.sessionId(),
                taskDtos,
                allComplete,
                nextQuestion
        );
    }
}
