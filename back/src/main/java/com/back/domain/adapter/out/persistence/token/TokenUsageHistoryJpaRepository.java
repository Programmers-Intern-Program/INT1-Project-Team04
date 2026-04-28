package com.back.domain.adapter.out.persistence.token;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TokenUsageHistoryJpaRepository extends JpaRepository<TokenUsageHistoryJpaEntity, String> {
    
    @Query("SELECT h FROM TokenUsageHistoryJpaEntity h WHERE h.user.id = :userId ORDER BY h.createdAt DESC")
    List<TokenUsageHistoryJpaEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            Pageable pageable
    );
    
    // 관리자용 쿼리
    @Query("SELECT h FROM TokenUsageHistoryJpaEntity h JOIN FETCH h.user ORDER BY h.createdAt DESC")
    List<TokenUsageHistoryJpaEntity> findRecentWithUser(Pageable pageable);
}
