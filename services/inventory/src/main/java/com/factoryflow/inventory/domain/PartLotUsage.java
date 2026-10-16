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

/** 완성차에 쓰인 부품 LOT. 추가만 하고 수정하지 않는다. lotId 가 null 이면 LOT 도입 전 재고에서 썼다. */
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
    private Long productionId;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(updatable = false)
    private Long lotId;

    @Column(nullable = false, updatable = false)
    private int qty;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PartLotUsage(String vin, Long productionId, Long partId, Long lotId, int qty) {
        this.vin = vin;
        this.productionId = productionId;
        this.partId = partId;
        this.lotId = lotId;
        this.qty = qty;
    }

    /** 그 차가 LOT 에서 qty 만큼 썼다. lotId 가 null 이면 LOT 도입 전 재고. */
    public static PartLotUsage use(
            String vin, Long productionId, Long partId, Long lotId, int qty) {
        return new PartLotUsage(vin, productionId, partId, lotId, qty);
    }
}
