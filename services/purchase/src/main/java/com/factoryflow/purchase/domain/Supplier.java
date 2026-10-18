package com.factoryflow.purchase.domain;

import com.factoryflow.common.web.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공급업체 (협력업체). 기본 데이터는 Flyway 로 넣는다. */
@Getter
@Entity
@Table(name = "supplier")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String contact;

    private Supplier(String name, String contact) {
        this.name = name;
        this.contact = contact;
    }

    public static Supplier register(String name, String contact) {
        return new Supplier(name, contact);
    }

    /** 이름과 연락처를 바꾼다. 이미 발주한 구매 건은 같은 업체(supplier_id)를 그대로 가리킨다. */
    public void change(String name, String contact) {
        this.name = name;
        this.contact = contact;
    }
}
