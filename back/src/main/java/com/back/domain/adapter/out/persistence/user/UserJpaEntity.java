package com.back.domain.adapter.out.persistence.user;

import com.back.domain.adapter.out.persistence.common.BaseTimeEntity;
import com.back.domain.model.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
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

    @Column(unique = true)
    private String email;

    @Column(length = 100)
    private String nickname;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public UserJpaEntity(String email, String nickname) {
        this.email = email;
        this.nickname = nickname;
    }

    public static UserJpaEntity from(User user) {
        UserJpaEntity entity = new UserJpaEntity(
                user.email(),
                user.nickname());
        entity.id = user.id();
        entity.deletedAt = user.deletedAt();
        return entity;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void withdraw() {
        this.email = null;
        this.nickname = null;
        this.deletedAt = LocalDateTime.now();
    }

    public User toDomain() {
        return new User(
                id,
                email,
                nickname,
                getCreatedAt(),
                deletedAt);
    }
}
