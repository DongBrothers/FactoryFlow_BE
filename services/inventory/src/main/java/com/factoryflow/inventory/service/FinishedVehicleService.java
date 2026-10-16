package com.factoryflow.inventory.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.FinishedVehicle;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.event.listener.payload.ProductionCompleted;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.FinishedVehicleRepository;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import com.factoryflow.inventory.repository.StockHistoryRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 완성차 재고와 생산에 쓴 부품 사용 처리 (docs/specs/production-completion.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinishedVehicleService {

    private final FinishedVehicleRepository finishedVehicleRepository;
    private final PartReservationRepository reservationRepository;
    private final PartRepository partRepository;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * production.completed: 그 생산 건의 RESERVED 예약 줄을 모두 CONSUMED 로 바꾸고(reserved −qty, available 그대로,
     * stock_history CONSUME) 완성차 재고를 한 대 넣는다. 이미 완성차가 있으면 건너뛰고, RESERVED 줄이 없으면
     * RESERVATION_NOT_FOUND (완성된 차는 부품이 모두 잡혀 있었다).
     */
    @Transactional
    public void stock(ProductionCompleted event) {
        if (isStocked(event.productionId())) {
            log.info("완성차가 이미 있어 건너뜀: productionId={}", event.productionId());
            return;
        }
        List<PartReservation> reservations = findReserved(event.productionId());
        if (reservations.isEmpty()) {
            throw new CustomException(InventoryErrorCode.RESERVATION_NOT_FOUND);
        }
        consume(event.productionId(), reservations);
        finishedVehicleRepository.save(
                FinishedVehicle.stock(
                        event.vin(), event.productionId(), event.orderId(), event.productId()));
    }

    /** 예약 줄마다 부품의 잡은 수량을 줄이고 사용 이력을 남긴다. 부품은 한 번에 읽는다 (동시 예약과 version 으로 부딪힌다). */
    private void consume(Long productionId, List<PartReservation> reservations) {
        Map<Long, Part> partsById =
                findParts(reservations.stream().map(PartReservation::getPartId).toList());
        for (PartReservation reservation : reservations) {
            partsById.get(reservation.getPartId()).consume(reservation.getQty());
            reservation.consume();
            stockHistoryRepository.save(
                    StockHistory.consumeForProduction(
                            reservation.getPartId(), reservation.getQty(), productionId));
        }
    }

    private boolean isStocked(Long productionId) {
        return finishedVehicleRepository.existsByProductionId(productionId);
    }

    private List<PartReservation> findReserved(Long productionId) {
        return reservationRepository.findAllByProductionIdAndStatus(
                productionId, ReservationStatus.RESERVED);
    }

    private Map<Long, Part> findParts(List<Long> partIds) {
        return partRepository.findAllByIdInAndIsDeletedFalse(partIds).stream()
                .collect(Collectors.toMap(Part::getId, Function.identity()));
    }
}
