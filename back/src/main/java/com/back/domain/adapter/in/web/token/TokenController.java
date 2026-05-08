package com.back.domain.adapter.in.web.token;

import com.back.domain.application.command.GrantTokenCommand;
import com.back.domain.application.command.UseTokenCommand;
import com.back.domain.application.port.in.TokenManagementUseCase;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenResult;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Incoming Web Adapter] 토큰 관리 REST controller
 */
@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenManagementUseCase tokenManagementUseCase;

    @GetMapping("/balance/{userId}")
    @ResponseStatus(HttpStatus.OK)
    public TokenBalanceResponse getBalance(@PathVariable Long userId) {
        UserTokenResult result = tokenManagementUseCase.getBalance(userId);
        return TokenBalanceResponse.from(result);
    }

    @PostMapping("/use")
    @ResponseStatus(HttpStatus.OK)
    public TokenBalanceResponse useToken(@Valid @RequestBody UseTokenRequest request) {
        UseTokenCommand command = new UseTokenCommand(
                request.userId(),
                request.amount(),
                request.description(),
                request.referenceId()
        );
        
        UserTokenResult result = tokenManagementUseCase.useToken(command);
        return TokenBalanceResponse.from(result);
    }

    @PostMapping("/grant")
    @ResponseStatus(HttpStatus.OK)
    public TokenBalanceResponse grantToken(@Valid @RequestBody GrantTokenRequest request) {
        GrantTokenCommand command = new GrantTokenCommand(
                request.userId(),
                request.amount(),
                request.description()
        );
        
        UserTokenResult result = tokenManagementUseCase.grantToken(command);
        return TokenBalanceResponse.from(result);
    }

    @GetMapping("/history/{userId}")
    @ResponseStatus(HttpStatus.OK)
    public TokenUsageHistoryResponse getUsageHistory(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<TokenUsageHistoryResult> results = tokenManagementUseCase.getUsageHistory(userId, limit);
        return TokenUsageHistoryResponse.from(results);
    }
}
