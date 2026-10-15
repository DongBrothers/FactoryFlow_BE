package com.factoryflow.inventory.event.listener.payload;

import java.util.List;

/** 받은 production.material-requested 의 payload (docs/specs/events.md). */
public record MaterialRequested(Long productionId, List<MaterialRequestedPart> parts) {}
