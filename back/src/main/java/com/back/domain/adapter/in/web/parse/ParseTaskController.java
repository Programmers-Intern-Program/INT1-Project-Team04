package com.back.domain.adapter.in.web.parse;

import com.back.domain.application.command.ContinueParseCommand;
import com.back.domain.application.command.ParseTaskCommand;
import com.back.domain.application.port.in.ParseTaskUseCase;
import com.back.domain.application.result.ParseResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Incoming Web Adapter] 자연어 파싱 요청을 처리하는 REST controller
 */
@RestController
@RequestMapping("/api/parse")
@RequiredArgsConstructor
public class ParseTaskController {

    private final ParseTaskUseCase parseTaskUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public ParseResultResponse parse(@Valid @RequestBody ParseRequest request) {
        ParseTaskCommand command = new ParseTaskCommand(
            request.userId(),
            request.input()
        );

        ParseResult result = parseTaskUseCase.parse(command);

        // TODO: needsConfirmation=false인 태스크들은 자동으로 구독 생성하는 로직 추가 예정
        // TODO: 현재는 파싱 결과만 반환

        return ParseResultResponse.from(result);
    }

    @PostMapping("/continue/{sessionId}")
    @ResponseStatus(HttpStatus.OK)
    public ParseResultResponse continueParse(
        @PathVariable String sessionId,
        @Valid @RequestBody ContinueParseRequest request
    ) {
        ContinueParseCommand command = new ContinueParseCommand(
            request.userId(),
            sessionId,
            request.response()
        );

        ParseResult result = parseTaskUseCase.continueParse(command);

        // TODO: needsConfirmation=false가 되면 자동으로 구독 생성하는 로직 추가 예정
        // TODO: 현재는 파싱 결과만 반환

        return ParseResultResponse.from(result);
    }
}
