package com.factoryflow.order.domain;

import com.factoryflow.common.web.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문하는 고객. 영업 담당이 등록하고, 주문할 때 등록된 고객인지 확인한다 (docs/specs/customer.md). */
@Getter
@Entity
@Table(name = "customer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String contact;

    private Customer(String name, String contact) {
        this.name = name;
        this.contact = contact;
    }

    public static Customer register(String name, String contact) {
        return new Customer(name, contact);
    }
}
