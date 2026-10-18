package com.factoryflow.production.domain;

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

/** 제품 (고객이 주문하는 완성품). */
@Getter
@Entity
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    private Product(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static Product register(String code, String name) {
        return new Product(code, name);
    }

    /** 이름만 바꾼다. 코드는 바꾸지 않는다 (docs/specs/master-data.md). */
    public void rename(String name) {
        this.name = name;
    }
}
