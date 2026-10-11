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
}
