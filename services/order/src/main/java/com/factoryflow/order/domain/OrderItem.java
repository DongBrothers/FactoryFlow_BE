package com.factoryflow.order.domain;

import com.factoryflow.common.web.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
public class OrderItem extends BaseTimeEntity {

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

    /** 품목·주문 요약을 동시에 바꾸면(다른 큐의 차량 상태 변경) 늦은 쪽이 실패하고 재시도로 다시 요약한다. */
    @Version private Long version;

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
        // 폐기된 차는 대체 차가 대신한다. 대체 차가 아직 안 왔으면 접수로 센다 (docs/specs/defect-scrap.md)
        List<OrderStatus> statuses =
                new ArrayList<>(
                        vehicleStatuses.stream().filter(s -> s != OrderStatus.SCRAPPED).toList());
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
