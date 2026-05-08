package com.back.domain.adapter.in.web.subscriptionconversation;

import com.back.domain.application.service.subscriptionconversation.SubscriptionConversationService;

public record SubscriptionConversationMessageRequest(
        String conversationId,
        String message,
        ActionRequest action
) {

    SubscriptionConversationService.ActionRequest toServiceAction() {
        if (action == null) {
            return null;
        }
        return new SubscriptionConversationService.ActionRequest(action.type(), action.value());
    }

    public record ActionRequest(String type, String value) {
    }
}
