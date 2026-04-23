package com.back.domain.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.domain.adapter.out.persistence.user.UserJpaEntity;
import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("Persistence: 사용자 저장소 테스트")
class UserPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Test
    @DisplayName("활성 사용자만 이메일로 조회하고 탈퇴 사용자는 제외한다")
    void findsOnlyActiveUserByEmail() {
        UserJpaEntity active = userJpaRepository.save(new UserJpaEntity("active@example.com", "활성사용자"));
        UserJpaEntity withdrawn = userJpaRepository.save(new UserJpaEntity("withdrawn@example.com", "탈퇴사용자"));
        withdrawn.withdraw();

        assertThat(userJpaRepository.findByEmailAndDeletedAtIsNull("active@example.com"))
                .map(UserJpaEntity::getId)
                .contains(active.getId());
        assertThat(userJpaRepository.findByEmailAndDeletedAtIsNull("withdrawn@example.com")).isEmpty();
    }
}
