package com.factoryflow.auth.domain;

import com.factoryflow.common.web.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 직원 계정. 관리자가 발급한 대기 계정(PendingAccount)을 직원이 활성화(비번 설정)할 때 처음 저장된다. 로그인 ID 는 이메일. 고객은 로그인하지 않으므로 이
 * 테이블에 없다. (docs/specs/auth-account.md)
 */
@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부·토큰(sub) 식별용 UUID. 내부 참조·조인은 id 를 쓴다. */
    @Column(name = "public_id", nullable = false, length = 36, updatable = false)
    private String publicId;

    /** 로그인 ID. 소문자로 정규화해 저장한다. */
    @Column(nullable = false, length = 100)
    private String email;

    /** 비번 해시. */
    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    /** 역할 (role.id). */
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    private UserAccount(String email, String name, Long roleId, String encodedPassword) {
        this.publicId = UUID.randomUUID().toString();
        this.email = email;
        this.name = name;
        this.roleId = roleId;
        this.password = encodedPassword;
        this.status = AccountStatus.ACTIVE;
    }

    /** 활성화: 비번이 설정된 ACTIVE 계정을 만든다. */
    public static UserAccount create(
            String email, String name, Long roleId, String encodedPassword) {
        return new UserAccount(email, name, roleId, encodedPassword);
    }
}
