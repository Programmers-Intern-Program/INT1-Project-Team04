package com.back.domain.adapter.out.persistence.subscriptionconversation;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.model.notification.NotificationChannel;
import com.back.domain.model.subscription.SubscriptionConversationStatus;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "subscription_conversation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionConversationJpaEntity extends BaseTimeEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "parse_session_id", length = 100)
    private String parseSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubscriptionConversationStatus status;

    @Column(name = "draft_query", columnDefinition = "TEXT")
    private String draftQuery;

    @Column(name = "draft_domain_id")
    private Long draftDomainId;

    @Column(name = "draft_domain_name", length = 100)
    private String draftDomainName;

    @Column(name = "draft_intent", length = 100)
    private String draftIntent;

    @Column(name = "draft_tool_name", length = 100)
    private String draftToolName;

    @Column(name = "draft_monitoring_params", columnDefinition = "TEXT")
    private String draftMonitoringParams;

    @Column(name = "draft_cron_expr", length = 50)
    private String draftCronExpr;

    @Enumerated(EnumType.STRING)
    @Column(name = "draft_notification_channel", length = 30)
    private NotificationChannel draftNotificationChannel;

    @Column(name = "draft_notification_target_address", columnDefinition = "TEXT")
    private String draftNotificationTargetAddress;

    @Column(name = "last_assistant_message", columnDefinition = "TEXT")
    private String lastAssistantMessage;

    public SubscriptionConversationJpaEntity(Long userId) {
        this.id = UuidGenerator.create();
        this.userId = userId;
        this.status = SubscriptionConversationStatus.COLLECTING;
    }

    public void updateParsedDraft(
            String parseSessionId,
            String draftQuery,
            Long draftDomainId,
            String draftDomainName,
            String draftIntent,
            String draftToolName,
            String draftMonitoringParams,
            String draftCronExpr,
            NotificationChannel draftNotificationChannel,
            String draftNotificationTargetAddress,
            String lastAssistantMessage,
            SubscriptionConversationStatus status
    ) {
        this.parseSessionId = parseSessionId;
        this.draftQuery = draftQuery;
        this.draftDomainId = draftDomainId;
        this.draftDomainName = draftDomainName;
        this.draftIntent = draftIntent;
        this.draftToolName = draftToolName;
        this.draftMonitoringParams = draftMonitoringParams;
        this.draftCronExpr = draftCronExpr;
        this.draftNotificationChannel = draftNotificationChannel;
        this.draftNotificationTargetAddress = draftNotificationTargetAddress;
        this.lastAssistantMessage = lastAssistantMessage;
        this.status = status;
    }

    public void updateCadence(String cronExpr) {
        this.draftCronExpr = cronExpr;
    }

    public void updateChannel(NotificationChannel channel, String targetAddress) {
        this.draftNotificationChannel = channel;
        this.draftNotificationTargetAddress = targetAddress;
    }

    public void updateStatus(SubscriptionConversationStatus status, String lastAssistantMessage) {
        this.status = status;
        this.lastAssistantMessage = lastAssistantMessage;
    }
}
