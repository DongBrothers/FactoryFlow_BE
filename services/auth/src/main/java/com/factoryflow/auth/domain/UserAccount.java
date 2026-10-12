package com.factoryflow.auth.domain;

import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.common.web.BaseEntity;
import com.factoryflow.common.web.exception.CustomException;
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
 * 사원 계정. 관리자가 발급하고(사번·이메일·이름·역할) 사원이 활성화(비번 설정)한다. 고객은 로그인하지 않으므로 이 테이블에 없다.
 * (docs/specs/auth-account.md)
 */
@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부·토큰(sub) 식별용 UUID. 내부 참조·조인은 id 를 쓴다. */
    @Column(name = "public_id", nullable = false, length = 36, updatable = false)
    private String publicId;

    @Column(name = "employee_no", nullable = false, length = 20)
    private String employeeNo;

    @Column(nullable = false, length = 100)
    private String email;

    /** 비번 해시. 발급(INVITED) 직후엔 null, 활성화 때 설정된다. */
    @Column(length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    private UserAccount(String employeeNo, String email, String name, Role role) {
        this.publicId = UUID.randomUUID().toString();
        this.employeeNo = employeeNo;
        this.email = email;
        this.name = name;
        this.role = role;
        this.status = AccountStatus.INVITED;
    }

    /** 관리자 발급: 비번 없이 INVITED 상태로 생성한다. */
    public static UserAccount invite(String employeeNo, String email, String name, Role role) {
        return new UserAccount(employeeNo, email, name, role);
    }

    /** 사원 활성화: INVITED 상태에서만 비번을 설정하고 ACTIVE 로 전환한다. */
    public void activate(String encodedPassword) {
        if (this.status != AccountStatus.INVITED) {
            throw new CustomException(AuthErrorCode.ACCOUNT_NOT_INVITED);
        }
        this.password = encodedPassword;
        this.status = AccountStatus.ACTIVE;
    }
}
