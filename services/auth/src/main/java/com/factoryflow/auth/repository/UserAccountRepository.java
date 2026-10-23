package com.factoryflow.auth.repository;

import com.factoryflow.auth.domain.UserAccount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    boolean existsByEmail(String email);

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findByPublicId(String publicId);

    List<UserAccount> findAllByPublicIdIn(Collection<String> publicIds);
}
