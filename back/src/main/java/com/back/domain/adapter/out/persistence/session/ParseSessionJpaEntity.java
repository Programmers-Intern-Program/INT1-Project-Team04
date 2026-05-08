package com.back.domain.adapter.out.persistence.session;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.model.session.ParseSession;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "parse_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParseSessionJpaEntity extends BaseTimeEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "original_input", columnDefinition = "TEXT", nullable = false)
    private String originalInput;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String currentResult;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String messages;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Column(name = "max_turns", nullable = false)
    private int maxTurns;

    @Column(name = "is_complete", nullable = false)
    private boolean complete;

    public ParseSessionJpaEntity(Long userId, String originalInput, String currentResult,
                                  String messages, int turnCount, int maxTurns, boolean complete) {
        this.id = UuidGenerator.create();
        this.userId = userId;
        this.originalInput = originalInput;
        this.currentResult = currentResult;
        this.messages = messages;
        this.turnCount = turnCount;
        this.maxTurns = maxTurns;
        this.complete = complete;
    }

    public void updateFrom(ParseSession session, String currentResultJson, String messagesJson) {
        this.currentResult = currentResultJson;
        this.messages = messagesJson;
        this.turnCount = session.getTurnCount();
        this.complete = session.isComplete();
    }
}