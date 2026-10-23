package com.factoryflow.auth.repository;

import com.factoryflow.auth.domain.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    void deleteAllByRoleId(Long roleId);
}
