package com.back.domain.application.port.out;

import com.back.domain.application.result.ParsedTask;
import java.util.List;

/**
 * [Outgoing Port] 자연어를 파싱하여 태스크 리스트로 변환하는 AI 포트
 */
public interface ParseNaturalLanguagePort {

    List<ParsedTask> parse(String userInput);

    List<ParsedTask> continueParse(List<ConversationMessage> history);

    record ConversationMessage(String role, String content) {}
}