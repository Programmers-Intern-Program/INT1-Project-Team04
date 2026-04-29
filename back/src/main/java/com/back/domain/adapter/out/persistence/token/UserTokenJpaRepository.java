package com.back.domain.adapter.out.persistence.token;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTokenJpaRepository extends JpaRepository<UserTokenJpaEntity, String> {
    
    @Query("SELECT t FROM UserTokenJpaEntity t WHERE t.user.id = :userId")
    Optional<UserTokenJpaEntity> findByUserId(@Param("userId") Long userId);
    
    // 관리자용 쿼리
    @Query("SELECT t FROM UserTokenJpaEntity t JOIN FETCH t.user ORDER BY t.lastUpdatedAt DESC")
    List<UserTokenJpaEntity> findAllWithUser(Pageable pageable);
    
    @Query("SELECT SUM(t.totalGranted) FROM UserTokenJpaEntity t")
    Long sumTotalGranted();
    
    @Query("SELECT SUM(t.totalUsed) FROM UserTokenJpaEntity t")
    Long sumTotalUsed();
    
    @Query("SELECT SUM(t.balance) FROM UserTokenJpaEntity t")
    Long sumBalance();
    
    @Query("SELECT COUNT(DISTINCT t.user.id) FROM UserTokenJpaEntity t WHERE t.balance > 0")
    Long countActiveUsers();
}
