package com.back.domain.adapter.out.persistence.subscriptionconversation;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.model.subscription.SubscriptionMonitoringConfig;
import com.back.global.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "subscription_monitoring_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionMonitoringConfigJpaEntity extends BaseTimeEntity {

    @Id
    private String id;

    @Column(name = "subscription_id", nullable = false, length = 100)
    private String subscriptionId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(nullable = false, length = 100)
    private String intent;

    @Column(name = "parameters_json", nullable = false, columnDefinition = "TEXT")
    private String parametersJson;

    public SubscriptionMonitoringConfigJpaEntity(
            String subscriptionId,
            String toolName,
            String intent,
            String parametersJson
    ) {
        this.id = UuidGenerator.create();
        this.subscriptionId = subscriptionId;
        this.toolName = toolName;
        this.intent = intent;
        this.parametersJson = parametersJson;
    }

    public SubscriptionMonitoringConfig toDomain() {
        return new SubscriptionMonitoringConfig(subscriptionId, toolName, intent, parametersJson);
    }
}
