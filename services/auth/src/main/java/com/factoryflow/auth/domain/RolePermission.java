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

/** 역할이 가진 권한. 역할별 권한의 원본은 이 표 하나다 (docs/specs/role-permissions.md). 행은 마이그레이션으로만 넣는다. */
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
}
