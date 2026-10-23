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
 * 사원 역할. 관리자가 발급 시 배정한다. 1인 1역할. 행은 마이그레이션(V5)으로만 넣는다. 역할별 권한은 gateway 가 code 로 확인한다
 * (docs/specs/auth-account.md, docs/specs/role-permissions.md). 라인 작업자 WORKER 는 생산 PRODUCTION 으로
 * 합쳤다 (#118).
 */
@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    /** user_account.role_id 가 가리킨다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT role 클레임·API 에 쓰는 값 (예: SALES). */
    @Column(nullable = false, length = 20)
    private String code;

    /** 화면에 보이는 이름 (예: 영업). */
    @Column(nullable = false, length = 20)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
