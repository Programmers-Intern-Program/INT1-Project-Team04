package com.back.domain.application.port.out;

import com.back.domain.model.session.ParseSession;
import java.util.Optional;

/**
 * [Outgoing Port] 파싱 세션 조회 포트
 */
public interface LoadParseSessionPort {
    Optional<ParseSession> loadById(String sessionId);
}