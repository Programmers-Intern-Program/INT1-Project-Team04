package com.back.domain.application.port.out;

import com.back.domain.model.user.User;
import java.util.Optional;

/**
 * [Outbound Port] 사용자 정보를 외부 저장소에서 불러오기 위한 interface
 */
public interface LoadUserPort {
    Optional<User> loadById(Long id);
}
