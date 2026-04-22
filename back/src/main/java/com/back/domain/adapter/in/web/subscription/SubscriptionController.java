package com.back.domain.adapter.in.web.subscription;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.port.in.CreateSubscriptionUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Incoming Web Adapter] 구독 생성 요청을 처리하는 REST controller
 */
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final CreateSubscriptionUseCase createSubscriptionUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse create(@Valid @RequestBody CreateSubscriptionRequest request) {
        return SubscriptionResponse.from(createSubscriptionUseCase.create(
                new CreateSubscriptionCommand(request.userId(), request.domainId(), request.query(), request.cronExpr())
        ));
    }
}
