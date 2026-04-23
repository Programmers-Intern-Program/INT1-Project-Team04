package com.back.domain.adapter.out.persistence.session;

import com.back.domain.application.port.out.LoadParseSessionPort;
import com.back.domain.application.port.out.SaveParseSessionPort;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.model.session.ParseSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParseSessionPersistenceAdapter implements SaveParseSessionPort, LoadParseSessionPort {

    private final ParseSessionJpaRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public ParseSession save(ParseSession session) {
        try {
            String resultJson = objectMapper.writeValueAsString(session.getCurrentResult());
            String messagesJson = objectMapper.writeValueAsString(session.getMessages());

            ParseSessionJpaEntity entity = repository.findById(session.getId())
                    .orElseGet(() -> new ParseSessionJpaEntity(
                            session.getUserId(),
                            session.getOriginalInput(),
                            resultJson,
                            messagesJson,
                            session.getTurnCount(),
                            session.getMaxTurns(),
                            session.isComplete()
                    ));

            if (repository.existsById(session.getId())) {
                entity.updateFrom(session, resultJson, messagesJson);
            }

            ParseSessionJpaEntity saved = repository.save(entity);

            return toDomain(saved);
        } catch (Exception e) {
            log.error("세션 저장 실패", e);
            throw new RuntimeException("세션 저장 실패", e);
        }
    }

    @Override
    public Optional<ParseSession> loadById(String sessionId) {
        return repository.findById(sessionId).map(this::toDomain);
    }

    private ParseSession toDomain(ParseSessionJpaEntity entity) {
        try {
            List<ParsedTask> tasks = objectMapper.readValue(
                    entity.getCurrentResult(),
                    new TypeReference<List<ParsedTask>>() {}
            );
            List<ParseSession.ConversationMessage> messages = objectMapper.readValue(
                    entity.getMessages(),
                    new TypeReference<List<ParseSession.ConversationMessage>>() {}
            );

            ParseSession session = new ParseSession(
                    entity.getId(),
                    entity.getUserId(),
                    entity.getOriginalInput(),
                    tasks
            );
            session.incrementTurn(); // 생성자에서 turnCount=0이므로
            for (ParseSession.ConversationMessage msg : messages) {
                session.addMessage(msg.role(), msg.content());
            }

            return session;
        } catch (Exception e) {
            log.error("세션 변환 실패", e);
            return null;
        }
    }
}
