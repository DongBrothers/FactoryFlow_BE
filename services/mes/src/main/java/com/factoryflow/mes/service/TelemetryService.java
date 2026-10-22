package com.factoryflow.mes.service;

import com.factoryflow.common.web.exception.CustomException;
import com.factoryflow.mes.config.TelemetryProperties;
import com.factoryflow.mes.domain.Equipment;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.StepTelemetry;
import com.factoryflow.mes.domain.TelemetryParameter;
import com.factoryflow.mes.domain.TelemetrySource;
import com.factoryflow.mes.dto.response.EquipmentTelemetryResponse;
import com.factoryflow.mes.dto.response.EquipmentTelemetrySampleResponse;
import com.factoryflow.mes.dto.response.StepTelemetryResponse;
import com.factoryflow.mes.dto.response.TelemetryLimitResponse;
import com.factoryflow.mes.dto.response.TelemetryRunResponse;
import com.factoryflow.mes.dto.response.TelemetrySampleResponse;
import com.factoryflow.mes.event.listener.payload.EquipmentStepTelemetry;
import com.factoryflow.mes.exception.MesErrorCode;
import com.factoryflow.mes.repository.EquipmentRepository;
import com.factoryflow.mes.repository.StepTelemetryRepository;
import com.factoryflow.mes.repository.WorkOrderRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로봇 공정 센서값: MongoDB 시계열 컬렉션에 저장하고 공정·설비 기준으로 조회한다 (docs/specs/process-telemetry.md). */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final StepTelemetryRepository stepTelemetryRepository;
    private final MongoTemplate mongoTemplate;
    private final WorkOrderRepository workOrderRepository;
    private final EquipmentRepository equipmentRepository;
    private final TelemetryProperties properties;

    /**
     * equipment.step-telemetry: 센서값마다 문서 하나로 저장한다. 작업지시가 없거나 끝났어도 받은 값은 기록으로 남긴다. 공정 상태(MySQL)와 같은
     * 트랜잭션이 아니다. 메시지당 상한을 넘으면 메모리에 올리지 않고 TELEMETRY_TOO_LARGE (재시도 후 DLQ).
     */
    public void record(EquipmentStepTelemetry event) {
        requireWithinLimit(event);
        ensureCollection();
        TelemetrySource source =
                new TelemetrySource(
                        event.equipmentCode(),
                        event.workOrderId(),
                        event.process(),
                        event.dispatchSeq());
        List<StepTelemetry> rows =
                event.samples().stream()
                        .map(s -> StepTelemetry.record(s.at(), source, s.values()))
                        .toList();
        stepTelemetryRepository.saveAll(rows);
    }

    /** 작업지시 한 공정의 센서값, 회차별·시간 순. 작업지시가 없으면 WORK_ORDER_NOT_FOUND. */
    @Transactional(readOnly = true)
    public StepTelemetryResponse findStepTelemetry(Long workOrderId, ProductionProcess process) {
        requireWorkOrder(workOrderId);
        List<StepTelemetry> rows = findStepRows(workOrderId, process);
        boolean truncated = rows.size() > properties.maxQuerySamples();
        List<TelemetryRunResponse> runs =
                byDispatchSeq(firstAllowed(rows)).values().stream()
                        .map(TelemetryService::run)
                        .toList();
        return StepTelemetryResponse.of(workOrderId, process, limitsOf(process), runs, truncated);
    }

    /** 설비 하나의 [from, to) 센서값, 시간 순. 설비가 없으면 EQUIPMENT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public EquipmentTelemetryResponse findEquipmentTelemetry(
            String equipmentCode, LocalDateTime from, LocalDateTime to) {
        Equipment equipment = findEquipment(equipmentCode);
        List<StepTelemetry> rows = findEquipmentRows(equipmentCode, toInstant(from), toInstant(to));
        boolean truncated = rows.size() > properties.maxQuerySamples();
        List<EquipmentTelemetrySampleResponse> samples =
                firstAllowed(rows).stream().map(EquipmentTelemetrySampleResponse::from).toList();
        return EquipmentTelemetryResponse.of(
                equipmentCode, limitsOf(equipment.getProcess()), samples, truncated);
    }

    /**
     * 시계열 컬렉션(StepTelemetry 의 @TimeSeries: timeField, metaField, 90일 보관)이 없으면 만든다. 처음 저장할 때 한 번
     * 만든다. 동시에 만들다 다른 쪽이 먼저 만들었으면 그대로 쓴다.
     */
    private void ensureCollection() {
        if (mongoTemplate.collectionExists(StepTelemetry.class)) {
            return;
        }
        try {
            mongoTemplate.createCollection(StepTelemetry.class);
        } catch (DataAccessException e) {
            if (!mongoTemplate.collectionExists(StepTelemetry.class)) {
                throw e;
            }
            log.info("센서값 컬렉션을 다른 쪽이 먼저 만들어 그대로 씀");
        }
    }

    /** 메시지당 센서값 수 상한 (OOM 방지). 비정상적으로 큰 메시지는 저장하지 않는다. */
    private void requireWithinLimit(EquipmentStepTelemetry event) {
        if (event.samples().size() > properties.maxSamplesPerMessage()) {
            log.warn(
                    "센서값 메시지가 너무 커 저장하지 않음: equipment={} workOrderId={} samples={} max={}",
                    event.equipmentCode(),
                    event.workOrderId(),
                    event.samples().size(),
                    properties.maxSamplesPerMessage());
            throw new CustomException(MesErrorCode.TELEMETRY_TOO_LARGE);
        }
    }

    /** 조회 상한까지만 (상한을 넘었는지 알려고 하나 더 읽는다). */
    private List<StepTelemetry> firstAllowed(List<StepTelemetry> rows) {
        return rows.size() > properties.maxQuerySamples()
                ? rows.subList(0, properties.maxQuerySamples())
                : rows;
    }

    /** 조회 상한 + 1. 하나 더 읽어 상한을 넘었는지 안다. */
    private Limit queryLimit() {
        return Limit.of(properties.maxQuerySamples() + 1);
    }

    /** 회차 순으로 묶는다 (rows 는 이미 회차·시간 순). */
    private static Map<Integer, List<StepTelemetry>> byDispatchSeq(List<StepTelemetry> rows) {
        Map<Integer, List<StepTelemetry>> runs = new LinkedHashMap<>();
        rows.forEach(
                t ->
                        runs.computeIfAbsent(t.getMeta().dispatchSeq(), seq -> new ArrayList<>())
                                .add(t));
        return runs;
    }

    /** 한 회차의 센서값. 한 회차는 한 설비가 한다. */
    private static TelemetryRunResponse run(List<StepTelemetry> rows) {
        TelemetrySource source = rows.get(0).getMeta();
        return TelemetryRunResponse.of(
                source.dispatchSeq(),
                source.equipmentCode(),
                rows.stream().map(TelemetrySampleResponse::from).toList());
    }

    private static List<TelemetryLimitResponse> limitsOf(ProductionProcess process) {
        return TelemetryParameter.of(process).stream().map(TelemetryLimitResponse::from).toList();
    }

    private static Instant toInstant(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private void requireWorkOrder(Long workOrderId) {
        if (!workOrderRepository.existsById(workOrderId)) {
            throw new CustomException(MesErrorCode.WORK_ORDER_NOT_FOUND);
        }
    }

    private Equipment findEquipment(String equipmentCode) {
        return equipmentRepository
                .findByCodeAndIsDeletedFalse(equipmentCode)
                .orElseThrow(() -> new CustomException(MesErrorCode.EQUIPMENT_NOT_FOUND));
    }

    private List<StepTelemetry> findStepRows(Long workOrderId, ProductionProcess process) {
        return stepTelemetryRepository
                .findAllByMetaWorkOrderIdAndMetaProcessOrderByMetaDispatchSeqAscMeasuredAtAsc(
                        workOrderId, process, queryLimit());
    }

    /** [from, to): 시작은 포함, 끝은 뺀다. */
    private List<StepTelemetry> findEquipmentRows(String equipmentCode, Instant from, Instant to) {
        return stepTelemetryRepository
                .findAllByMetaEquipmentCodeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                        equipmentCode,
                        Range.of(Bound.inclusive(from), Bound.exclusive(to)),
                        queryLimit());
    }
}
