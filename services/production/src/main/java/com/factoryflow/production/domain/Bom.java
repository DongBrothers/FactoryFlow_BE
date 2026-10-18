package com.factoryflow.production.domain;

import com.factoryflow.common.web.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** BOM: 제품 1개에 필요한 부품과 수량. partId 는 inventory 의 부품 (논리 참조). */
@Getter
@Entity
@Table(name = "bom")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long partId;

    @Column(nullable = false)
    private int qtyPerUnit;

    private Bom(Long productId, Long partId, int qtyPerUnit) {
        this.productId = productId;
        this.partId = partId;
        this.qtyPerUnit = qtyPerUnit;
    }

    public static Bom of(Long productId, Long partId, int qtyPerUnit) {
        return new Bom(productId, partId, qtyPerUnit);
    }

    /** BOM 수정 때 남거나 다시 넣은 부품: 수량을 바꾸고, 지웠던 줄이면 되살린다. */
    public void keep(int qtyPerUnit) {
        this.qtyPerUnit = qtyPerUnit;
        restore();
    }
}
