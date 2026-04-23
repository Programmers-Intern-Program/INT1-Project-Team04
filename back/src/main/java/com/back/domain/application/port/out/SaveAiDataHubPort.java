package com.back.domain.application.port.out;

import com.back.domain.model.hub.AiDataHub;

/**
 * [Outbound Port] AI 관련 통합 데이터(대화, 임베딩, 메타데이터 등)를 저장하기 위한 인터페이
 */
public interface SaveAiDataHubPort {
    AiDataHub save(AiDataHub aiDataHub);
}
