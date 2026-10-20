package com.factoryflow.mes.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 차 하나를 빈 설비들에 시도하는 규칙 (LineDispatcher.tryEquipments). 설비 쪽 실패는 설비를 읽은 직후 설비가 멈추거나 다른 차가 먼저 잡는
 * 경쟁에서만 생겨 통합 테스트로는 매번 재현할 수 없으므로, 설비마다 결과를 정해 준 시도 함수로 확인한다.
 */
class LineDispatcherTest {

    @Test
    void equipmentFailureRetriesTheSameCarOnTheNextEquipment() {
        // 설비 [E1, E2], 차 A: E1 이 그사이 멈췄으면 A 가 E2 를 받는다 (뒤의 차 B 에게 밀리지 않음)
        Deque<String> free = new ArrayDeque<>(List.of("E1", "E2"));
        List<String> tried = new ArrayList<>();

        DispatchOutcome outcome =
                LineDispatcher.tryEquipments(
                        free,
                        attempt(
                                tried,
                                Map.of(
                                        "E1", DispatchOutcome.EQUIPMENT_FAILED,
                                        "E2", DispatchOutcome.DISPATCHED)));

        assertThat(outcome).isEqualTo(DispatchOutcome.DISPATCHED);
        assertThat(tried).containsExactly("E1", "E2");
        assertThat(free).isEmpty();
    }

    @Test
    void carFailureLeavesTheEquipmentForTheNextCar() {
        Deque<String> free = new ArrayDeque<>(List.of("E1", "E2"));
        List<String> tried = new ArrayList<>();

        DispatchOutcome outcome =
                LineDispatcher.tryEquipments(
                        free, attempt(tried, Map.of("E1", DispatchOutcome.CAR_FAILED)));

        assertThat(outcome).isEqualTo(DispatchOutcome.CAR_FAILED);
        assertThat(tried).containsExactly("E1");
        assertThat(free).containsExactly("E1", "E2");
    }

    @Test
    void runningOutOfEquipmentIsNotTheCarsFault() {
        Deque<String> free = new ArrayDeque<>(List.of("E1", "E2"));
        List<String> tried = new ArrayList<>();

        DispatchOutcome outcome =
                LineDispatcher.tryEquipments(
                        free,
                        attempt(
                                tried,
                                Map.of(
                                        "E1", DispatchOutcome.EQUIPMENT_FAILED,
                                        "E2", DispatchOutcome.EQUIPMENT_FAILED)));

        assertThat(outcome).isEqualTo(DispatchOutcome.EQUIPMENT_FAILED);
        assertThat(tried).containsExactly("E1", "E2");
        assertThat(free).isEmpty();
    }

    private static Function<String, DispatchOutcome> attempt(
            List<String> tried, Map<String, DispatchOutcome> outcomes) {
        return code -> {
            tried.add(code);
            return outcomes.get(code);
        };
    }
}
