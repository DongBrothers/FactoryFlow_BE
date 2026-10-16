package com.factoryflow.inventory.repository;

import com.factoryflow.inventory.domain.PartReservation;
import com.factoryflow.inventory.domain.ReservationStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartReservationRepository extends JpaRepository<PartReservation, Long> {

    boolean existsByProductionId(Long productionId);

    boolean existsByProductionIdAndStatus(Long productionId, ReservationStatus status);

    List<PartReservation> findAllByPartIdAndStatusOrderByIdAsc(
            Long partId, ReservationStatus status);

    List<PartReservation> findAllByProductionIdAndStatus(
            Long productionId, ReservationStatus status);

    List<PartReservation> findAllByPartIdInAndStatus(
            Collection<Long> partIds, ReservationStatus status);
}
