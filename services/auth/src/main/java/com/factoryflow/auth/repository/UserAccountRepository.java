package com.factoryflow.auth.repository;

import com.factoryflow.auth.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    boolean existsByEmployeeNo(String employeeNo);

    boolean existsByEmail(String email);
}
