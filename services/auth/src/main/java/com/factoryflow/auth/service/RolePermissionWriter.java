package com.factoryflow.auth.service;

import com.factoryflow.auth.domain.RolePermission;
import com.factoryflow.auth.repository.RolePermissionRepository;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 역할의 권한을 한 트랜잭션에 통째로 바꾼다. Redis 쓰기는 RoleService 가 트랜잭션이 끝난 뒤 한다. */
@Component
@RequiredArgsConstructor
public class RolePermissionWriter {

    private final RolePermissionRepository rolePermissionRepository;

    @Transactional
    public void replace(Long roleId, Collection<Long> permissionIds) {
        rolePermissionRepository.deleteAllByRoleId(roleId);
        // 같은 (역할, 권한) 을 다시 넣으므로 지우기를 먼저 내보낸다 (Hibernate 는 넣기를 지우기보다 먼저 보낸다)
        rolePermissionRepository.flush();
        rolePermissionRepository.saveAll(
                permissionIds.stream().map(id -> RolePermission.grant(roleId, id)).toList());
    }
}
