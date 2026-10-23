package com.factoryflow.purchase.client.inventory;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.purchase.client.inventory.dto.response.PartResponse;
import com.factoryflow.purchase.client.inventory.dto.response.PartShortageResponse;
import com.factoryflow.purchase.client.inventory.dto.response.PartShortagesResponse;
import com.factoryflow.purchase.client.inventory.dto.response.PartsResponse;
import com.factoryflow.purchase.exception.PurchaseErrorCode;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/internal/parts")
public interface PartClient {

    Logger log = LoggerFactory.getLogger(PartClient.class);

    @GetExchange
    PartsResponse getParts(@RequestParam("ids") Collection<Long> ids);

    /**
     * 있는 부품만 돌려준다 (삭제된 부품은 빠진다). 실패, 시간 초과, 본문이나 data 가 없는 비정상 응답은 모두 INVENTORY_UNAVAILABLE(503)
     * 이다. 정상 응답의 빈 data 만 빈 목록이다.
     */
    default List<PartResponse> findParts(Collection<Long> ids) {
        PartsResponse response;
        try {
            response = getParts(ids);
        } catch (RestClientException e) {
            log.warn("[Purchase] inventory 부품 조회 실패: {}", e.getMessage());
            throw new CustomException(PurchaseErrorCode.INVENTORY_UNAVAILABLE);
        }
        if (response == null || response.data() == null) {
            log.warn("[Purchase] inventory 부품 조회 응답에 data 가 없음");
            throw new CustomException(PurchaseErrorCode.INVENTORY_UNAVAILABLE);
        }
        return response.data();
    }

    @GetExchange("/shortages")
    PartShortagesResponse getShortages();

    /**
     * 부족 부품 (docs/specs/purchase-suggestion.md). 실패, 시간 초과, 본문이나 data 가 없는 비정상 응답은
     * INVENTORY_UNAVAILABLE(503).
     */
    default List<PartShortageResponse> findShortages() {
        PartShortagesResponse response;
        try {
            response = getShortages();
        } catch (RestClientException e) {
            log.warn("[Purchase] inventory 부족 부품 조회 실패: {}", e.getMessage());
            throw new CustomException(PurchaseErrorCode.INVENTORY_UNAVAILABLE);
        }
        if (response == null || response.data() == null) {
            log.warn("[Purchase] inventory 부족 부품 응답에 data 가 없음");
            throw new CustomException(PurchaseErrorCode.INVENTORY_UNAVAILABLE);
        }
        return response.data();
    }
}
