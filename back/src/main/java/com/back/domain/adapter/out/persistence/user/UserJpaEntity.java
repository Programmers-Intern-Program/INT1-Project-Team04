package com.back.domain.adapter.out.persistence.user;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.model.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DB의 'users' 테이블과 매핑되는 영속성 엔티티
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserJpaEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "discord_token", columnDefinition = "TEXT")
    private String discordToken;

    public UserJpaEntity(String email, String discordToken) {
        this.email = email;
        this.discordToken = discordToken;
    }

    public static UserJpaEntity from(User user) {
        UserJpaEntity entity = new UserJpaEntity(
                user.email(),
                user.discordToken());
        entity.id = user.id();
        return entity;
    }

    public User toDomain() {
        return new User(
                id,
                email,
                discordToken,
                getCreatedAt());
    }
}
