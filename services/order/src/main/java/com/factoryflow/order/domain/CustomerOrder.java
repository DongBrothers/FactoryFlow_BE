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
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.Collection;
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

    /** 품목·주문 요약을 동시에 바꾸면(다른 큐의 차량 상태 변경) 늦은 쪽이 실패하고 재시도로 다시 요약한다. */
    @Version private Long version;

    /** 진행 중인 취소 요청 회차의 시각. 답이 다 오면 비운다 (docs/specs/order-cancel.md). */
    private LocalDateTime cancelRequestedAt;

    private CustomerOrder(String orderNo, Long customerId, OrderStatus status) {
        this.orderNo = orderNo;
        this.customerId = customerId;
        this.status = status;
    }

    /** 품목 상태로 주문 상태를 다시 계산한다. 차량·품목 상태를 바꾼 트랜잭션에서 함께 부른다. */
    public void refreshStatus(Collection<OrderStatus> itemStatuses) {
        this.status = OrderStatus.summarize(itemStatuses);
    }

    /** 취소 요청 회차를 시작한다. */
    public void startCancelRound(LocalDateTime now) {
        this.cancelRequestedAt = now;
    }

    /** 취소 요청 회차가 끝났다 (답이 다 옴). */
    public void finishCancelRound() {
        this.cancelRequestedAt = null;
    }

    public boolean isCancelRoundOpen() {
        return cancelRequestedAt != null;
    }

    public static CustomerOrder receive(Long customerId) {
        return new CustomerOrder(UUID.randomUUID().toString(), customerId, OrderStatus.RECEIVED);
    }
}
