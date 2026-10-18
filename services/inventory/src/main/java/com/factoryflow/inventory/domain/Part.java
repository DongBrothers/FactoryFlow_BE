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

    private Part(String code, String name, String unit) {
        this.code = code;
        this.name = name;
        this.unit = unit;
    }

    /** 새 부품. 재고 0 으로 시작하고 LOT 도 없다 (첫 입고 때 생긴다, docs/specs/master-data.md). */
    public static Part register(String code, String name, String unit) {
        return new Part(code, name, unit);
    }

    /** 이름과 단위만 바꾼다. 코드와 수량은 바꾸지 않는다. */
    public void changeDetails(String name, String unit) {
        this.name = name;
        this.unit = unit;
    }

    /** 창고 실물 수량 (바로 쓸 수 있는 수량 + 생산에 잡혀 있는 수량). */
    public int onHand() {
        return available + reserved;
    }

    /** 바로 쓸 수 있는 수량에서 qty 만큼 생산용으로 잡는다. */
    public void reserve(int qty) {
        this.available -= qty;
        this.reserved += qty;
    }

    /** 구매한 qty 가 입고됐다. 바로 쓸 수 있는 수량이 는다. */
    public void receive(int qty) {
        this.available += qty;
    }

    /** 생산 취소로 잡아 둔 qty 를 풀었다. 바로 쓸 수 있는 수량으로 돌아간다. */
    public void release(int qty) {
        this.reserved -= qty;
        this.available += qty;
    }

    /** 생산에 잡아 둔 qty 를 썼다. 바로 쓸 수 있는 수량은 그대로다. */
    public void consume(int qty) {
        this.reserved -= qty;
    }
}
