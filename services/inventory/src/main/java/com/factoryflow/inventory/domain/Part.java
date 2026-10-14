package com.factoryflow.inventory.domain;

import com.factoryflow.common.web.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 부품과 재고. 창고 실물 = available + reserved. 동시에 같은 부품을 바꾸면 version 으로 막는다. */
@Getter
@Entity
@Table(name = "part")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Part extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Version
    @Column(nullable = false)
    private Long version;

    /** 바로 쓸 수 있는 수량에서 qty 만큼 생산용으로 잡는다. */
    public void reserve(int qty) {
        this.available -= qty;
        this.reserved += qty;
    }
}
