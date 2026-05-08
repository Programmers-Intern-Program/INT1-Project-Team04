package com.back.domain.application.port.in;

import com.back.domain.application.command.ContinueParseCommand;
import com.back.domain.application.command.ParseTaskCommand;
import com.back.domain.application.result.ParseResult;

/**
 * [Incoming Port] 자연어 파싱 유스케이스
 */
public interface ParseTaskUseCase {
    ParseResult parse(ParseTaskCommand command);
    ParseResult continueParse(ContinueParseCommand command);
}