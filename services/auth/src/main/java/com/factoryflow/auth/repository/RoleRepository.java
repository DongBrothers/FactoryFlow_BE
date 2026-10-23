package com.factoryflow.auth.repository;

import com.factoryflow.auth.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findAllByOrderBySortOrderAsc();

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);
}
