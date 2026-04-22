package com.back.domain.application.port.in;

import com.back.domain.application.command.CreateSubscriptionCommand;
import com.back.domain.application.result.SubscriptionResult;

public interface CreateSubscriptionUseCase {

    SubscriptionResult create(CreateSubscriptionCommand command);
}
