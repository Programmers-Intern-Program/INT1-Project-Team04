package com.back.domain.application.service;

import com.back.domain.application.command.GrantTokenCommand;
import com.back.domain.application.command.UseTokenCommand;
import com.back.domain.application.port.in.TokenManagementUseCase;
import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.application.port.out.LoadUserTokenPort;
import com.back.domain.application.port.out.SaveTokenUsageHistoryPort;
import com.back.domain.application.port.out.SaveUserTokenPort;
import com.back.domain.application.result.TokenUsageHistoryResult;
import com.back.domain.application.result.UserTokenResult;
import com.back.domain.model.token.TokenUsageHistory;
import com.back.domain.model.token.TokenUsageType;
import com.back.domain.model.token.UserToken;
import com.back.global.common.UuidGenerator;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Domain Service] 토큰 관리 유스케이스 구현
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TokenManagementService implements TokenManagementUseCase {

    private final LoadUserPort loadUserPort;
    private final LoadUserTokenPort loadUserTokenPort;
    private final SaveUserTokenPort saveUserTokenPort;
    private final SaveTokenUsageHistoryPort saveTokenUsageHistoryPort;

    @Override
    @Transactional(readOnly = true)
    public UserTokenResult getBalance(Long userId) {
        log.info("토큰 잔액 조회 - userId: {}", userId);
        
        var user = loadUserPort.loadById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        var userToken = loadUserTokenPort.loadByUserId(userId)
                .orElseGet(() -> createInitialToken(user.id()));

        return UserTokenResult.from(userToken);
    }

    @Override
    public UserTokenResult useToken(UseTokenCommand command) {
        log.info("토큰 사용 - userId: {}, amount: {}, description: {}", 
                command.userId(), command.amount(), command.description());

        if (command.amount() <= 0) {
            throw new ApiException(ErrorCode.INVALID_TOKEN_AMOUNT);
        }

        var user = loadUserPort.loadById(command.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        var userToken = loadUserTokenPort.loadByUserId(command.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_NOT_FOUND));

        if (!userToken.hasEnoughBalance(command.amount())) {
            log.warn("토큰 부족 - userId: {}, balance: {}, required: {}", 
                    command.userId(), userToken.balance(), command.amount());
            throw new ApiException(ErrorCode.INSUFFICIENT_TOKEN);
        }

        int balanceBefore = userToken.balance();
        var updatedToken = userToken.decreaseBalance(command.amount());
        var savedToken = saveUserTokenPort.save(updatedToken);

        var history = TokenUsageHistory.create(
                UuidGenerator.create(),
                user,
                TokenUsageType.USE,
                command.amount(),
                balanceBefore,
                savedToken.balance(),
                command.description(),
                command.referenceId()
        );
        saveTokenUsageHistoryPort.save(history);

        log.info("토큰 사용 완료 - userId: {}, balance: {} -> {}", 
                command.userId(), balanceBefore, savedToken.balance());

        return UserTokenResult.from(savedToken);
    }

    @Override
    public UserTokenResult grantToken(GrantTokenCommand command) {
        log.info("토큰 부여 - userId: {}, amount: {}, description: {}", 
                command.userId(), command.amount(), command.description());

        if (command.amount() <= 0) {
            throw new ApiException(ErrorCode.INVALID_TOKEN_AMOUNT);
        }

        var user = loadUserPort.loadById(command.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        var userToken = loadUserTokenPort.loadByUserId(command.userId())
                .orElseGet(() -> createInitialToken(user.id()));

        int balanceBefore = userToken.balance();
        var updatedToken = userToken.increaseBalance(command.amount());
        var savedToken = saveUserTokenPort.save(updatedToken);

        var history = TokenUsageHistory.create(
                UuidGenerator.create(),
                user,
                TokenUsageType.GRANT,
                command.amount(),
                balanceBefore,
                savedToken.balance(),
                command.description(),
                null
        );
        saveTokenUsageHistoryPort.save(history);

        log.info("토큰 부여 완료 - userId: {}, balance: {} -> {}", 
                command.userId(), balanceBefore, savedToken.balance());

        return UserTokenResult.from(savedToken);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TokenUsageHistoryResult> getUsageHistory(Long userId, int limit) {
        log.info("토큰 사용 내역 조회 - userId: {}, limit: {}", userId, limit);

        var user = loadUserPort.loadById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        var histories = saveTokenUsageHistoryPort.findByUserId(userId, limit);
        return TokenUsageHistoryResult.fromList(histories);
    }

    private UserToken createInitialToken(Long userId) {
        log.info("초기 토큰 생성 - userId: {}", userId);
        
        var user = loadUserPort.loadById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        return new UserToken(
                null,
                user,
                0,
                0,
                0,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
