package com.back.domain.application.port.out;

import com.back.domain.model.session.ParseSession;
import java.util.Optional;

/**
 * [Outgoing Port] 파싱 세션 저장 포트
 */
public interface SaveParseSessionPort {
    ParseSession save(ParseSession session);
}