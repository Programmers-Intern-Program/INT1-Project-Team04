package com.back.domain.application.port.out;

import com.back.domain.model.hub.AiDataHub;
import java.util.List;

public interface LoadRecentAiDataHubPort {

    List<AiDataHub> loadRecentByUserIdAndToolId(Long userId, Long toolId, int limit);
}
