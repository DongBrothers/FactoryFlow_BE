package com.factoryflow.simulator.event.listener;

import com.factoryflow.simulator.config.EventQueueConfig;
import com.factoryflow.simulator.event.listener.payload.ReceivedEvent;
import com.factoryflow.simulator.event.listener.payload.StepDispatched;
import com.factoryflow.simulator.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * mes 의 배정을 받는다. DB 가 없어 ProcessedEventGuard 대신 EquipmentService 가 eventId 를 메모리로 기억해 중복을 거른다
 * (docs/specs/simulator.md).
 */
@Component
@RequiredArgsConstructor
public class MesEventListener {

    private final EquipmentService equipmentService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = EventQueueConfig.STEP_DISPATCHED_QUEUE)
    public void onStepDispatched(ReceivedEvent event) {
        StepDispatched dispatch = objectMapper.treeToValue(event.payload(), StepDispatched.class);
        equipmentService.acceptDispatch(event.eventId(), event.traceId(), dispatch);
    }
}
