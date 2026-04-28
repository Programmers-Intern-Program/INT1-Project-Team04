package com.back.domain.application.service;

import com.back.domain.application.command.ContinueParseCommand;
import com.back.domain.application.command.ParseTaskCommand;
import com.back.domain.application.command.UseTokenCommand;
import com.back.domain.application.port.in.ParseTaskUseCase;
import com.back.domain.application.port.in.TokenManagementUseCase;
import com.back.domain.application.port.out.LoadParseSessionPort;
import com.back.domain.application.port.out.ParseNaturalLanguagePort;
import com.back.domain.application.port.out.SaveParseSessionPort;
import com.back.domain.application.result.ParseResult;
import com.back.domain.application.result.ParsedTask;
import com.back.domain.model.session.ParseSession;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Domain Service] 자연어 파싱 유스케이스 구현
 * 사용자의 자연어 입력을 AI로 파싱하고 멀티턴 대화를 관리합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ParseTaskService implements ParseTaskUseCase {

    private final ParseNaturalLanguagePort parseNaturalLanguagePort;
    private final SaveParseSessionPort saveParseSessionPort;
    private final LoadParseSessionPort loadParseSessionPort;
    private final TokenManagementUseCase tokenManagementUseCase;

    @Override
    public ParseResult parse(ParseTaskCommand command) {
        log.info("자연어 파싱 시작 - userId: {}, input: {}", command.userId(), command.input());

        // Step 0: 토큰 차감 (10 토큰)
        String inputPreview = command.input().length() > 50 
                ? command.input().substring(0, 50) + "..." 
                : command.input();
        
        try {
            tokenManagementUseCase.useToken(new UseTokenCommand(
                command.userId(),
                10,
                "AI 자연어 파싱: " + inputPreview,
                null
            ));
            log.info("토큰 차감 완료 - userId: {}, amount: 10", command.userId());
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.INSUFFICIENT_TOKEN) {
                log.warn("토큰 부족으로 파싱 실패 - userId: {}", command.userId());
                throw e;
            }
            // TOKEN_NOT_FOUND의 경우 초기 토큰이 없는 것이므로 계속 진행하지 않음
            log.error("토큰 차감 실패 - userId: {}, error: {}", command.userId(), e.getMessage());
            throw e;
        }

        // Step 1: AI로 자연어 파싱
        List<ParsedTask> tasks = parseNaturalLanguagePort.parse(command.input());
        
        if (tasks.isEmpty()) {
            log.warn("파싱 결과가 비어있음 - userId: {}, input: {}", command.userId(), command.input());
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }

        // Step 2: 세션 생성
        ParseSession session = new ParseSession(
            UuidGenerator.create(),
            command.userId(),
            command.input(),
            tasks
        );

        // Step 3: 대화 이력 추가
        session.addMessage("user", command.input());
        session.incrementTurn();

        // Step 4: 세션 저장
        ParseSession saved = saveParseSessionPort.save(session);
        
        log.info("파싱 세션 생성 완료 - sessionId: {}, turnCount: {}, complete: {}", 
            saved.getId(), saved.getTurnCount(), saved.isComplete());

        // Step 5: 확인 질문이 있으면 로그 출력
        if (!saved.isComplete()) {
            String question = saved.getFirstConfirmationQuestion();
            log.info("추가 확인 필요 - sessionId: {}, question: {}", saved.getId(), question);
        }

        return new ParseResult(saved.getId(), saved.getCurrentResult());
    }

    @Override
    public ParseResult continueParse(ContinueParseCommand command) {
        log.info("후속 파싱 시작 - sessionId: {}, userId: {}, response: {}", 
            command.sessionId(), command.userId(), command.response());

        // Step 1: 기존 세션 조회
        ParseSession session = loadParseSessionPort.loadById(command.sessionId())
            .orElseThrow(() -> {
                log.error("세션을 찾을 수 없음 - sessionId: {}", command.sessionId());
                return new ApiException(ErrorCode.SESSION_NOT_FOUND);
            });

        // Step 2: 사용자 ID 검증
        if (!session.getUserId().equals(command.userId())) {
            log.error("세션 소유자가 일치하지 않음 - sessionId: {}, expectedUserId: {}, actualUserId: {}", 
                command.sessionId(), session.getUserId(), command.userId());
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND);
        }

        // Step 3: 이미 완료된 세션인지 확인
        if (session.isComplete()) {
            log.info("이미 완료된 세션 - sessionId: {}", command.sessionId());
            return new ParseResult(session.getId(), session.getCurrentResult());
        }

        // Step 4: 최대 턴 수 확인
        if (session.isMaxTurnsExceeded()) {
            log.warn("최대 턴 수 초과 - sessionId: {}, turnCount: {}, maxTurns: {}", 
                command.sessionId(), session.getTurnCount(), session.getMaxTurns());
            session.forceComplete();
            ParseSession saved = saveParseSessionPort.save(session);
            return new ParseResult(saved.getId(), saved.getCurrentResult());
        }

        // Step 5: 토큰 차감 (5 토큰)
        String responsePreview = command.response().length() > 50 
                ? command.response().substring(0, 50) + "..." 
                : command.response();
        
        try {
            tokenManagementUseCase.useToken(new UseTokenCommand(
                command.userId(),
                5,
                "AI 후속 파싱: " + responsePreview,
                command.sessionId()
            ));
            log.info("토큰 차감 완료 - userId: {}, sessionId: {}, amount: 5", 
                    command.userId(), command.sessionId());
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.INSUFFICIENT_TOKEN) {
                log.warn("토큰 부족으로 후속 파싱 실패 - userId: {}, sessionId: {}", 
                        command.userId(), command.sessionId());
                throw e;
            }
            log.error("토큰 차감 실패 - userId: {}, sessionId: {}, error: {}", 
                    command.userId(), command.sessionId(), e.getMessage());
            throw e;
        }

        // Step 6: 사용자 응답 추가
        session.addMessage("user", command.response());

        // Step 7: AI 후속 파싱 (대화 이력 포함)
        List<ParseNaturalLanguagePort.ConversationMessage> history = session.getMessages().stream()
            .map(m -> new ParseNaturalLanguagePort.ConversationMessage(m.role(), m.content()))
            .toList();

        List<ParsedTask> updatedTasks = parseNaturalLanguagePort.continueParse(history);

        if (updatedTasks.isEmpty()) {
            log.warn("후속 파싱 결과가 비어있음 - sessionId: {}", command.sessionId());
            throw new ApiException(ErrorCode.AI_PARSE_FAILED);
        }

        // Step 8: 결과 업데이트
        session.updateResult(updatedTasks);
        session.incrementTurn();

        // Step 9: 세션 저장
        ParseSession saved = saveParseSessionPort.save(session);

        log.info("후속 파싱 완료 - sessionId: {}, turnCount: {}, complete: {}", 
            saved.getId(), saved.getTurnCount(), saved.isComplete());

        // Step 10: 여전히 확인이 필요한지 로그 출력
        if (!saved.isComplete()) {
            String question = saved.getFirstConfirmationQuestion();
            log.info("추가 확인 필요 - sessionId: {}, question: {}", saved.getId(), question);
        }

        return new ParseResult(saved.getId(), saved.getCurrentResult());
    }
}
