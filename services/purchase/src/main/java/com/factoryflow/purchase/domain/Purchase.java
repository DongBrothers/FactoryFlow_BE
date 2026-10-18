package com.factoryflow.purchase.domain;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 구매 건: 부품을 사 온다. 구매 담당이 부품·수량·공급업체를 넣어 만들면 바로 발주(ORDERED)다 (docs/specs/purchase-manual.md). partId
 * 는 inventory 의 부품 (논리 참조), partCode·partName 은 발주 당시 스냅샷이다.
 */
@Getter
@Entity
@Table(name = "purchase")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Purchase extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long partId;

    @Column(nullable = false, updatable = false, length = 50)
    private String partCode;

    @Column(nullable = false, updatable = false, length = 100)
    private String partName;

    @Column(nullable = false)
    private int qty;

    private Long supplierId;

    /** 발주한 직원 public_id (gateway 의 X-User-Id). 자동 요청 시절의 구매 건은 비어 있다. */
    @Column(length = 36, updatable = false)
    private String orderedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PurchaseStatus status;

    private LocalDateTime orderedAt;

    private LocalDateTime receivedAt;

    /** 같은 구매 건을 동시에 입고하면 늦은 쪽이 실패한다. */
    @Version private Long version;

    private Purchase(
            Long partId,
            String partCode,
            String partName,
            int qty,
            Long supplierId,
            String orderedBy,
            LocalDateTime now) {
        this.partId = partId;
        this.partCode = partCode;
        this.partName = partName;
        this.qty = qty;
        this.supplierId = supplierId;
        this.orderedBy = orderedBy;
        this.status = PurchaseStatus.ORDERED;
        this.orderedAt = now;
    }

    /** 구매 담당이 공급업체에 바로 발주한다. 부품 코드·이름은 inventory 에서 받은 스냅샷이다. */
    public static Purchase order(
            Long partId,
            String partCode,
            String partName,
            int qty,
            Long supplierId,
            String orderedBy,
            LocalDateTime now) {
        return new Purchase(partId, partCode, partName, qty, supplierId, orderedBy, now);
    }

    public boolean isOrdered() {
        return status == PurchaseStatus.ORDERED;
    }

    /** 물건이 들어왔다. 발주 상태인지는 서비스가 먼저 확인한다. */
    public void receive(LocalDateTime now) {
        this.status = PurchaseStatus.RECEIVED;
        this.receivedAt = now;
    }
}
