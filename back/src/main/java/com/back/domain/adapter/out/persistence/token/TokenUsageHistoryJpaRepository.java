package com.back.domain.adapter.out.persistence.token;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TokenUsageHistoryJpaRepository extends JpaRepository<TokenUsageHistoryJpaEntity, String> {
    
    @Query("SELECT h FROM TokenUsageHistoryJpaEntity h WHERE h.user.id = :userId ORDER BY h.createdAt DESC")
    List<TokenUsageHistoryJpaEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable
    );
}
