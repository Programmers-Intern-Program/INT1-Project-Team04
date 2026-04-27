package com.back.domain.model.session;

import com.back.domain.application.result.ParsedTask;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 멀티 턴 파싱 세션 도메인 모델
 */
public class ParseSession {

    private String id;
    private Long userId;
    private String originalInput;
    private List<ParsedTask> currentResult;
    private List<ConversationMessage> messages;
    private int turnCount;
    private int maxTurns;
    private boolean complete;
    private LocalDateTime createdAt;

    public ParseSession(String id, Long userId, String originalInput, List<ParsedTask> currentResult) {
        this.id = id;
        this.userId = userId;
        this.originalInput = originalInput;
        this.currentResult = new ArrayList<>(currentResult);
        this.messages = new ArrayList<>();
        this.turnCount = 0;
        this.maxTurns = 3;
        this.complete = currentResult.stream().allMatch(t -> !t.needsConfirmation());
        this.createdAt = LocalDateTime.now();
    }

    public void updateResult(List<ParsedTask> newResult) {
        this.currentResult = new ArrayList<>(newResult);
        this.complete = newResult.stream().allMatch(t -> !t.needsConfirmation());
    }

    public void addMessage(String role, String content) {
        this.messages.add(new ConversationMessage(role, content));
    }

    public void incrementTurn() {
        this.turnCount++;
    }

    public boolean isMaxTurnsExceeded() {
        return this.turnCount > this.maxTurns;
    }

    public void forceComplete() {
        this.currentResult = this.currentResult.stream()
                .map(t -> new ParsedTask(
                        t.intent(), t.domainName(), t.query(), t.condition(),
                        t.cronExpr(), t.channel(), t.apiType(), t.target(),
                        t.urls(), t.confidence(), false, ""
                ))
                .toList();
        this.complete = true;
    }

    public String getFirstConfirmationQuestion() {
        return currentResult.stream()
                .filter(t -> t.needsConfirmation() && !t.confirmationQuestion().isEmpty())
                .map(ParsedTask::confirmationQuestion)
                .findFirst()
                .orElse(null);
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getOriginalInput() { return originalInput; }
    public List<ParsedTask> getCurrentResult() { return currentResult; }
    public List<ConversationMessage> getMessages() { return messages; }
    public int getTurnCount() { return turnCount; }
    public int getMaxTurns() { return maxTurns; }
    public boolean isComplete() { return complete; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public record ConversationMessage(String role, String content) {}
}
