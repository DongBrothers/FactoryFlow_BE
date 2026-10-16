package com.factoryflow.inventory.domain;

import com.factoryflow.common.web.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 부품 LOT: 입고 한 번. remainingQty 는 아직 쓰지 않은 수량이다. 입고·소모가 부품 행도 함께 바꾸므로 동시 변경은 부품 version 이 막는다
 * (docs/specs/part-lot.md).
 */
@Getter
@Entity
@Table(name = "part_lot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartLot extends BaseEntity {

    private static final DateTimeFormatter LOT_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(nullable = false, updatable = false, length = 20)
    private String lotNo;

    @Column(nullable = false, updatable = false)
    private Long purchaseId;

    @Column(nullable = false, updatable = false)
    private int receivedQty;

    @Column(nullable = false)
    private int remainingQty;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    private PartLot(Long partId, Long purchaseId, int qty, LocalDateTime receivedAt) {
        this.partId = partId;
        this.lotNo = lotNo(purchaseId, receivedAt);
        this.purchaseId = purchaseId;
        this.receivedQty = qty;
        this.remainingQty = qty;
        this.receivedAt = receivedAt;
    }

    /** 구매 건 하나의 입고로 LOT 을 만든다. 번호는 LOT-{입고일 yyMMdd}-{purchaseId, 6자리가 안 되면 앞을 0 으로 채움}. */
    public static PartLot receive(Long partId, Long purchaseId, int qty, LocalDateTime now) {
        return new PartLot(partId, purchaseId, qty, now);
    }

    /** 이 LOT 에서 최대 wanted 만큼 쓴다. 실제로 쓴 수량을 돌려준다. */
    public int take(int wanted) {
        int taken = Math.min(wanted, remainingQty);
        this.remainingQty -= taken;
        return taken;
    }

    private static String lotNo(Long purchaseId, LocalDateTime receivedAt) {
        return String.format("LOT-%s-%06d", receivedAt.format(LOT_DATE), purchaseId);
    }
}
