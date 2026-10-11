package com.factoryflow.order.domain;

import com.factoryflow.common.web.BaseEntity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 고객 주문. 밖으로는 orderNo 만 노출하고, 서비스 간 참조와 이벤트는 id 를 쓴다. */
@Getter
@Entity
@Table(name = "customer_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerOrder extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, columnDefinition = "char(36)")
    private String orderNo;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    private CustomerOrder(String orderNo, Long customerId, OrderStatus status) {
        this.orderNo = orderNo;
        this.customerId = customerId;
        this.status = status;
    }

    public static CustomerOrder receive(Long customerId) {
        return new CustomerOrder(UUID.randomUUID().toString(), customerId, OrderStatus.RECEIVED);
    }
}
