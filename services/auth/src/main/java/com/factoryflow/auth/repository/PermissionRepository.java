package com.factoryflow.auth.repository;

import com.factoryflow.auth.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {}
