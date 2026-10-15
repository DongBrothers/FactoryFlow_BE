package com.factoryflow.inventory.service;

import com.factoryflow.common.event.EventPublisher;
import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.inventory.domain.Part;
import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import com.factoryflow.inventory.domain.StockHistory;
import com.factoryflow.inventory.event.listener.payload.MaterialRequestedPart;
import com.factoryflow.inventory.event.publisher.payload.InventoryReserved;
import com.factoryflow.inventory.event.publisher.payload.ReservationDeferred;
import com.factoryflow.inventory.event.publisher.payload.ReservationShortage;
import com.factoryflow.inventory.event.publisher.payload.ShortageDetected;
import com.factoryflow.inventory.exception.InventoryErrorCode;
import com.factoryflow.inventory.repository.PartRepository;
import com.factoryflow.inventory.repository.PartReservationRepository;
import com.factoryflow.inventory.repository.StockHistoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartReservationService {

    private final PartRepository partRepository;
    private final PartReservationRepository reservationRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final EventPublisher eventPublisher;

    /**
     * 생산 건에 필요한 부품을 partId 순서로 예약한다 (docs/specs/inventory-reservation.md). 같은 부품에 기다리는 줄이 없고 재고가
     * 충분하면 RESERVED, 아니면 WAITING 으로 뒤에 선다. 부족이 늘어난 부품은 inventory.shortage, 생산 건 결과는
     * inventory.reserved 또는 inventory.reservation-pending 으로 알린다. 이미 예약 줄이 있는 생산 건은 건너뛴다.
     */
    @Transactional
    public void reserve(Long productionId, List<MaterialRequestedPart> parts) {
        if (reservationRepository.existsByProductionId(productionId)) {
            log.info("예약 줄이 이미 있어 건너뜀: productionId={}", productionId);
            return;
        }
        Map<Long, Integer> qtyByPartId = mergeByPartId(parts);
        Map<Long, Part> partsById = loadParts(qtyByPartId.keySet());
        Map<Long, Integer> waitingQtyByPartId = waitingQtyByPartId(qtyByPartId.keySet());
        List<ReservationShortage> shortages = new ArrayList<>();
        qtyByPartId.forEach(
                (partId, qty) ->
                        reserveOne(productionId, partsById.get(partId), qty, waitingQtyByPartId)
                                .ifPresent(shortages::add));
        publishResult(productionId, shortages);
    }

    /** 같은 부품 줄은 수량을 합치고, partId 순서로 처리하도록 정렬한다. */
    private static Map<Long, Integer> mergeByPartId(List<MaterialRequestedPart> parts) {
        return parts.stream()
                .collect(
                        Collectors.toMap(
                                MaterialRequestedPart::partId,
                                MaterialRequestedPart::qty,
                                Integer::sum,
                                TreeMap::new));
    }

    /** 요청한 부품을 한 번에 읽는다. 하나라도 없거나 삭제됐으면 PART_NOT_FOUND. */
    private Map<Long, Part> loadParts(Set<Long> partIds) {
        Map<Long, Part> partsById =
                partRepository.findAllByIdInAndIsDeletedFalse(partIds).stream()
                        .collect(Collectors.toMap(Part::getId, Function.identity()));
        if (partsById.size() != partIds.size()) {
            throw new CustomException(InventoryErrorCode.PART_NOT_FOUND);
        }
        return partsById;
    }

    /** 부품별로 이미 기다리는(WAITING) 수량의 합. 기다리는 줄이 없는 부품은 빠진다. */
    private Map<Long, Integer> waitingQtyByPartId(Set<Long> partIds) {
        return reservationRepository
                .findAllByPartIdInAndStatus(partIds, ReservationStatus.WAITING)
                .stream()
                .collect(
                        Collectors.toMap(
                                PartReservation::getPartId, PartReservation::getQty, Integer::sum));
    }

    /**
     * 기다리는 줄이 없고 재고가 충분하면 RESERVED 로 재고를 잡고 empty 를 돌려준다. 아니면 WAITING 줄을 만들고, 부족이 늘었으면
     * inventory.shortage 를 발행한 뒤 그 줄을 돌려준다.
     */
    private Optional<ReservationShortage> reserveOne(
            Long productionId, Part part, int qty, Map<Long, Integer> waitingQtyByPartId) {
        int waiting = waitingQtyByPartId.getOrDefault(part.getId(), 0);
        if (waiting == 0 && part.getAvailable() >= qty) {
            part.reserve(qty);
            reservationRepository.save(PartReservation.reserved(productionId, part.getId(), qty));
            stockHistoryRepository.save(
                    StockHistory.reserveForProduction(part.getId(), qty, productionId));
            return Optional.empty();
        }
        reservationRepository.save(PartReservation.waiting(productionId, part.getId(), qty));
        int shortQty = shortQty(part.getAvailable(), waiting, qty);
        if (shortQty > 0) {
            eventPublisher.publish(
                    new ShortageDetected(part.getId(), part.getCode(), part.getName(), shortQty));
        }
        return Optional.of(new ReservationShortage(part.getId(), qty));
    }

    private void publishResult(Long productionId, List<ReservationShortage> shortages) {
        if (shortages.isEmpty()) {
            eventPublisher.publish(new InventoryReserved(productionId));
        } else {
            eventPublisher.publish(new ReservationDeferred(productionId, shortages));
        }
    }

    /** 대기 수량까지 포함해 이번 요청으로 새로 늘어난 부족분. 같은 부족을 두 번 구매하지 않기 위해서다. */
    private static int shortQty(int available, int waiting, int qty) {
        int before = Math.max(0, waiting - available);
        int after = Math.max(0, waiting + qty - available);
        return after - before;
    }
}
