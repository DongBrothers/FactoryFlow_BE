package com.factoryflow.purchase.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 구매 건: 부품을 사 온다. partId 는 inventory 의 부품 (논리 참조), partCode·partName 은 요청 당시 스냅샷이다. */
@Getter
@Entity
@Table(name = "purchase")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Purchase extends BaseEntity {

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

    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PurchaseStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private PurchaseSource source;

    private LocalDateTime orderedAt;

    private LocalDateTime receivedAt;

    /** 부족 합치기와 발주·입고가 같은 구매 건을 동시에 바꾸면 늦은 쪽이 실패한다. */
    @Version private Long version;

    private Purchase(Long partId, String partCode, String partName, int qty) {
        this.partId = partId;
        this.partCode = partCode;
        this.partName = partName;
        this.qty = qty;
        this.status = PurchaseStatus.REQUESTED;
        this.source = PurchaseSource.AUTO;
    }

    /** 재고 부족으로 자동 요청한다. */
    public static Purchase requestForShortage(
            Long partId, String partCode, String partName, int qty) {
        return new Purchase(partId, partCode, partName, qty);
    }

    public boolean isRequested() {
        return status == PurchaseStatus.REQUESTED;
    }

    public boolean isOrdered() {
        return status == PurchaseStatus.ORDERED;
    }

    /** 같은 부품의 부족이 더 왔다. 요청 상태일 때만 서비스가 부른다. */
    public void addQty(int qty) {
        this.qty += qty;
    }

    /** 공급업체에 발주했다. 요청 상태인지는 서비스가 먼저 확인한다. */
    public void order(Long supplierId, LocalDateTime now) {
        this.status = PurchaseStatus.ORDERED;
        this.supplierId = supplierId;
        this.orderedAt = now;
    }

    /** 물건이 들어왔다. 발주 상태인지는 서비스가 먼저 확인한다. */
    public void receive(LocalDateTime now) {
        this.status = PurchaseStatus.RECEIVED;
        this.receivedAt = now;
    }
}
