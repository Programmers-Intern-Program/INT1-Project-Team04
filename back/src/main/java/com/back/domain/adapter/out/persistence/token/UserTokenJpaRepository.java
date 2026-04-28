package com.back.domain.adapter.out.persistence.token;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTokenJpaRepository extends JpaRepository<UserTokenJpaEntity, String> {
    
    @Query("SELECT t FROM UserTokenJpaEntity t WHERE t.user.id = :userId")
    Optional<UserTokenJpaEntity> findByUserId(@Param("userId") Long userId);
}
