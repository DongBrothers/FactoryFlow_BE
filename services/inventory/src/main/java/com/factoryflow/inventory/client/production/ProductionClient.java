package com.factoryflow.inventory.client.production;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.client.production.dto.request.HoldMaterialRequest;
import com.factoryflow.inventory.client.production.dto.response.MaterialHoldApiResponse;
import com.factoryflow.inventory.client.production.dto.response.ProductionErrorResponse;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/internal/productions")
public interface ProductionClient {

    Logger log = LoggerFactory.getLogger(ProductionClient.class);

    /** production 이 거둘 차가 모자랄 때 주는 오류 코드. */
    String NOT_ENOUGH_UNRELEASED = "NOT_ENOUGH_UNRELEASED";

    @PostExchange("/material-holds")
    MaterialHoldApiResponse postMaterialHold(@RequestBody HoldMaterialRequest request);

    /**
     * 발행 전 차를 부품 대기로 되돌리고 그 생산 건 id 를 돌려준다 (docs/specs/stock-adjustment.md "찜한 부품 거둬들이기"). 거둬도
     * 모자라면(409 NOT_ENOUGH_UNRELEASED) INSUFFICIENT_AVAILABLE, 그 밖의 실패·시간 초과·data 없는 응답은
     * PRODUCTION_UNAVAILABLE(503).
     */
    default List<Long> holdForMaterial(HoldMaterialRequest request) {
        MaterialHoldApiResponse response;
        try {
            response = postMaterialHold(request);
        } catch (HttpClientErrorException.Conflict e) {
            if (isNotEnoughUnreleased(e)) {
                log.info("[Inventory] 발행 전 차에서 거둬도 모자람: partId={}", request.partId());
                throw new CustomException(InventoryErrorCode.INSUFFICIENT_AVAILABLE);
            }
            log.warn("[Inventory] production 부품 거두기 충돌: {}", e.getResponseBodyAsString());
            throw new CustomException(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("[Inventory] production 부품 거두기 요청 실패: {}", e.getMessage());
            throw new CustomException(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
        }
        if (response == null || response.data() == null) {
            log.warn("[Inventory] production 부품 거두기 응답에 data 가 없음");
            throw new CustomException(InventoryErrorCode.PRODUCTION_UNAVAILABLE);
        }
        return response.data().heldProductionIds();
    }

    /** 409 중 거둘 차가 모자라다는 것(NOT_ENOUGH_UNRELEASED)만 재고 부족이다. 동시 변경 충돌 같은 다른 409 는 아니다. */
    private static boolean isNotEnoughUnreleased(HttpClientErrorException.Conflict e) {
        try {
            ProductionErrorResponse body = e.getResponseBodyAs(ProductionErrorResponse.class);
            return body != null && NOT_ENOUGH_UNRELEASED.equals(body.code());
        } catch (RuntimeException parseFailure) {
            return false;
        }
    }
}
