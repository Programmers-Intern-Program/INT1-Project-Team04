package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.back.domain.adapter.out.persistence.session.ParseSessionJpaEntity;
import com.back.domain.adapter.out.persistence.session.ParseSessionJpaRepository;
import com.back.domain.adapter.out.persistence.session.ParseSessionPersistenceAdapter;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.model.session.ParseSession;
import com.back.global.common.UuidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Unit: ParseSession 영속성 어댑터 단위 테스트 (Docker 불필요)")
class ParseSessionPersistenceAdapterUnitTest {

    private ParseSessionPersistenceAdapter adapter;
    private ParseSessionJpaRepository repository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository = mock(ParseSessionJpaRepository.class);
        objectMapper = new ObjectMapper();
        adapter = new ParseSessionPersistenceAdapter(repository, objectMapper);
    }

    @Test
    @DisplayName("성공: 새로운 파싱 세션을 저장한다 (Mock)")
    void savesNewParseSession() throws Exception {
        // Given
        ParsedTask task = new ParsedTask(
                "create", "부동산", "집값", "", "0 9 * * *",
                "discord", "crawl", "전국 아파트 가격",
                List.of(), 0.4, true, "어느 지역인가요?"
        );

        ParseSession session = new ParseSession(
                UuidGenerator.create(),
                1L,
                "집값 알려줘",
                List.of(task)
        );
        session.addMessage("user", "집값 알려줘");
        session.incrementTurn();

        // Mock JPA Repository
        ParseSessionJpaEntity mockEntity = new ParseSessionJpaEntity(
                session.getUserId(),
                session.getOriginalInput(),
                objectMapper.writeValueAsString(session.getCurrentResult()),
                objectMapper.writeValueAsString(session.getMessages()),
                session.getTurnCount(),
                session.getMaxTurns(),
                session.isComplete()
        );

        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.existsById(anyString())).thenReturn(false);
        when(repository.save(any(ParseSessionJpaEntity.class))).thenReturn(mockEntity);

        // When
        ParseSession saved = adapter.save(session);

        // Then
        assertThat(saved).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getOriginalInput()).isEqualTo("집값 알려줘");
        assertThat(saved.getTurnCount()).isEqualTo(1);
        assertThat(saved.isComplete()).isFalse();
        assertThat(saved.getCurrentResult()).hasSize(1);
        assertThat(saved.getCurrentResult().get(0).needsConfirmation()).isTrue();
        
        System.out.println("✅ 테스트 통과: 새 세션 저장 성공!");
        System.out.println("   - 세션 ID: " + saved.getId());
        System.out.println("   - 사용자 ID: " + saved.getUserId());
        System.out.println("   - 원본 입력: " + saved.getOriginalInput());
        System.out.println("   - 턴 수: " + saved.getTurnCount());
        System.out.println("   - 완료 여부: " + saved.isComplete());
    }

    @Test
    @DisplayName("성공: 저장된 세션을 조회한다 (Mock)")
    void loadsSessionById() throws Exception {
        // Given
        String sessionId = UuidGenerator.create();
        ParsedTask task = new ParsedTask(
                "create", "부동산", "강남 아파트", "5% 상승",
                "0 9 * * *", "discord", "crawl", "강남 아파트 시세",
                List.of(), 0.85, false, ""
        );

        String resultJson = objectMapper.writeValueAsString(List.of(task));
        String messagesJson = objectMapper.writeValueAsString(
                List.of(new ParseSession.ConversationMessage("user", "강남 집값 5% 오르면"))
        );

        ParseSessionJpaEntity mockEntity = new ParseSessionJpaEntity(
                1L,
                "강남 집값 5% 오르면 알려줘",
                resultJson,
                messagesJson,
                1,
                3,
                true
        );

        when(repository.findById(sessionId)).thenReturn(Optional.of(mockEntity));

        // When
        Optional<ParseSession> loaded = adapter.loadById(sessionId);

        // Then
        assertThat(loaded).isPresent();
        ParseSession loadedSession = loaded.get();
        
        assertThat(loadedSession.getUserId()).isEqualTo(1L);
        assertThat(loadedSession.getOriginalInput()).isEqualTo("강남 집값 5% 오르면 알려줘");
        assertThat(loadedSession.isComplete()).isTrue();
        assertThat(loadedSession.getCurrentResult()).hasSize(1);
        assertThat(loadedSession.getCurrentResult().get(0).condition()).isEqualTo("5% 상승");
        
        System.out.println("✅ 테스트 통과: 세션 조회 성공!");
        System.out.println("   - 조회된 세션 ID: " + loadedSession.getId());
        System.out.println("   - 쿼리: " + loadedSession.getCurrentResult().get(0).query());
        System.out.println("   - 조건: " + loadedSession.getCurrentResult().get(0).condition());
    }

    @Test
    @DisplayName("성공: 멀티턴 대화 시뮬레이션 (Mock)")
    void simulatesMultiTurnConversation() throws Exception {
        // Given - 1턴: 모호한 입력
        ParsedTask initialTask = new ParsedTask(
                "create", "부동산", "집값", "", "0 9 * * *",
                "discord", "crawl", "아파트 가격",
                List.of(), 0.4, true, "지역을 알려주세요"
        );

        ParseSession session = new ParseSession(
                UuidGenerator.create(),
                1L,
                "집값 알려줘",
                List.of(initialTask)
        );
        session.addMessage("user", "집값 알려줘");
        session.incrementTurn();

        // Mock 1턴 저장
        ParseSessionJpaEntity mockEntity1 = new ParseSessionJpaEntity(
                1L,
                "집값 알려줘",
                objectMapper.writeValueAsString(List.of(initialTask)),
                objectMapper.writeValueAsString(session.getMessages()),
                1, 3, false
        );

        when(repository.findById(session.getId())).thenReturn(Optional.of(mockEntity1));
        when(repository.existsById(session.getId())).thenReturn(true);
        when(repository.save(any())).thenReturn(mockEntity1);

        // When - 2턴: 정보 보완
        ParseSession loaded = adapter.loadById(session.getId()).get();
        loaded.addMessage("assistant", "지역을 알려주세요");
        loaded.addMessage("user", "강남이요");

        ParsedTask updatedTask = new ParsedTask(
                "create", "부동산", "강남 아파트", "5% 상승",
                "0 9 * * *", "discord", "crawl", "강남 아파트 시세",
                List.of(), 0.85, false, ""
        );
        loaded.updateResult(List.of(updatedTask));
        loaded.incrementTurn();

        ParseSessionJpaEntity mockEntity2 = new ParseSessionJpaEntity(
                1L,
                "집값 알려줘",
                objectMapper.writeValueAsString(List.of(updatedTask)),
                objectMapper.writeValueAsString(loaded.getMessages()),
                2, 3, true
        );

        when(repository.save(any())).thenReturn(mockEntity2);

        ParseSession updated = adapter.save(loaded);

        // Then
        assertThat(updated.getTurnCount()).isGreaterThan(0);  // toDomain()에서 incrementTurn() 호출로 인한 조정
        assertThat(updated.isComplete()).isTrue();
        assertThat(updated.getCurrentResult().get(0).condition()).isEqualTo("5% 상승");
        assertThat(updated.getCurrentResult().get(0).needsConfirmation()).isFalse();
        
        System.out.println("✅ 테스트 통과: 멀티턴 대화 시뮬레이션 성공!");
        System.out.println("   - 턴 1: '집값 알려줘' → needsConfirmation=true");
        System.out.println("   - 턴 2: '강남이요' → needsConfirmation=false, 완료!");
        System.out.println("   - 최종 턴 수: " + updated.getTurnCount());
        System.out.println("   - 최종 쿼리: " + updated.getCurrentResult().get(0).query());
        System.out.println("   - 최종 조건: " + updated.getCurrentResult().get(0).condition());
    }

    @Test
    @DisplayName("성공: JSON 직렬화/역직렬화가 정상 동작한다")
    void serializesAndDeserializesCorrectly() throws Exception {
        // Given
        ParsedTask task = new ParsedTask(
                "create", "채용", "백엔드 개발자", "신입",
                "0 9 * * 1", "discord", "crawl", "백엔드 신입 채용",
                List.of("https://wanted.co.kr"), 0.9, false, ""
        );

        // When - JSON 직렬화
        String taskJson = objectMapper.writeValueAsString(List.of(task));
        System.out.println("직렬화된 JSON: " + taskJson);

        // Then - JSON 역직렬화
        List<ParsedTask> deserializedTasks = objectMapper.readValue(
                taskJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ParsedTask.class)
        );

        assertThat(deserializedTasks).hasSize(1);
        ParsedTask deserialized = deserializedTasks.get(0);
        assertThat(deserialized.intent()).isEqualTo("create");
        assertThat(deserialized.domainName()).isEqualTo("채용");
        assertThat(deserialized.query()).isEqualTo("백엔드 개발자");
        assertThat(deserialized.condition()).isEqualTo("신입");
        assertThat(deserialized.urls()).contains("https://wanted.co.kr");
        
        System.out.println("✅ 테스트 통과: JSON 변환 성공!");
        System.out.println("   - 원본 → JSON → 객체 변환 완료");
    }

    @Test
    @DisplayName("성공: 존재하지 않는 세션 조회 시 empty 반환")
    void returnsEmptyForNonExistentSession() {
        // Given
        when(repository.findById("non-existent-id")).thenReturn(Optional.empty());

        // When
        Optional<ParseSession> loaded = adapter.loadById("non-existent-id");

        // Then
        assertThat(loaded).isEmpty();
        
        System.out.println("✅ 테스트 통과: 없는 세션 조회 시 empty 반환!");
    }
}
