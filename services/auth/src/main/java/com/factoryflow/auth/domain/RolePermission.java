package com.factoryflow.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 역할이 가진 권한. 역할별 권한의 원본은 이 표 하나다 (docs/specs/role-permissions.md). 운영 중에는 관리자 API 로 바꾸고, gateway 는
 * Redis 로 받은 복사본으로 판단한다 (docs/specs/role-permission-admin.md).
 */
@Entity
@Table(name = "role_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** role.id */
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    /** permission.id */
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    private RolePermission(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    /** 역할에 권한을 준다. 관리자의 권한 바꾸기로만 만든다 (docs/specs/role-permission-admin.md). */
    public static RolePermission grant(Long roleId, Long permissionId) {
        return new RolePermission(roleId, permissionId);
    }
}
