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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 주문 품목. productCode, productName 은 주문 당시 스냅샷이다. */
@Getter
@Entity
@Table(name = "order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false, length = 50)
    private String productCode;

    @Column(nullable = false, updatable = false, length = 100)
    private String productName;

    @Column(nullable = false, updatable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(length = 255)
    private String failReason;

    private OrderItem(
            Long orderId, Long productId, String productCode, String productName, int qty) {
        this.orderId = orderId;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.qty = qty;
        this.status = OrderStatus.RECEIVED;
    }

    /**
     * 차량 상태들로 품목 상태를 다시 계산한다. 아직 알려지지 않은 차량(수량보다 모자란 만큼)은 RECEIVED 로 본다. 차량 상태를 바꾼 트랜잭션에서 함께 부른다.
     */
    public void refreshStatus(Collection<OrderStatus> vehicleStatuses) {
        List<OrderStatus> statuses = new ArrayList<>(vehicleStatuses);
        while (statuses.size() < qty) {
            statuses.add(OrderStatus.RECEIVED);
        }
        this.status = OrderStatus.summarize(statuses);
    }

    public static OrderItem receive(
            Long orderId, Long productId, String productCode, String productName, int qty) {
        return new OrderItem(orderId, productId, productCode, productName, qty);
    }
}
