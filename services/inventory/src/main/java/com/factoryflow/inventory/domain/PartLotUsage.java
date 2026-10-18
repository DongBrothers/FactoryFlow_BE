package com.factoryflow.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 완성차에 쓰인 부품 LOT. 추가만 하고 수정하지 않는다. 모든 재고에 LOT 이 있으므로 lotId 는 항상 있다 (#116). 부품은 LOT 이, 생산 건은 VIN 의
 * 완성차가 갖고 있다 (#123).
 */
@Getter
@Entity
@Table(name = "part_lot_usage")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartLotUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 17)
    private String vin;

    @Column(nullable = false, updatable = false)
    private Long lotId;

    @Column(nullable = false, updatable = false)
    private int qty;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PartLotUsage(String vin, Long lotId, int qty) {
        this.vin = vin;
        this.lotId = lotId;
        this.qty = qty;
    }

    /** 그 차가 LOT 에서 qty 만큼 썼다. */
    public static PartLotUsage use(String vin, Long lotId, int qty) {
        return new PartLotUsage(vin, lotId, qty);
    }
}
