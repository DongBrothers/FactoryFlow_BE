package com.factoryflow.production.event.publisher;

import com.factoryflow.common.event.DomainEvent;
import java.util.List;

public record MaterialRequested(Long productionId, List<MaterialRequestedPart> parts)
        implements DomainEvent {

    @Override
    public String eventName() {
        return "production.material-requested";
    }
}
