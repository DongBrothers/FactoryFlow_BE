package com.factoryflow.common.web;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * 소프트 삭제(is_deleted)가 있는 기준 데이터(제품, BOM, 부품, 공급업체, 설비). 사람이 지울 수 있어 조회는 삭제되지 않은 것만 본다. 업무 데이터는 지우지
 * 않으므로 {@link BaseTimeEntity} 를 쓴다.
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity extends BaseTimeEntity {

    @Column(nullable = false)
    private boolean isDeleted = false;

    public void delete() {
        this.isDeleted = true;
    }
}
