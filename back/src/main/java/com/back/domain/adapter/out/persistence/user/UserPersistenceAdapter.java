package com.back.domain.adapter.out.persistence.user;

import com.back.domain.application.port.out.LoadUserPort;
import com.back.domain.model.user.User;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [Persistence Adapter] 영속성 포트(LoadUserPort)를 구현하며 인프라 계층(JPA)과 도메인 계층(User)을 연결
 */
@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements LoadUserPort {

    private final UserJpaRepository userJpaRepository;

    public Optional<User> loadById(Long userId) {
        return userJpaRepository.findById(userId)
                .map(entity -> new User(
                        entity.getId(),
                        entity.getEmail(),
                        entity.getDiscordToken(),
                        entity.getCreatedAt()
                ));
    }
}
