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

/** 권한. gateway 가 API 마다 필요한 권한을 code 로 확인한다. 행은 마이그레이션으로만 넣는다 (docs/specs/role-permissions.md). */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT permissions 클레임에 들어가는 값 (예: ORDER_WRITE). */
    @Column(nullable = false, length = 30)
    private String code;

    /** 화면에 보이는 이름 (예: 주문 쓰기). */
    @Column(nullable = false, length = 30)
    private String name;
}
