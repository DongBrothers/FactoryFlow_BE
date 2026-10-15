package com.factoryflow.production.event.listener.payload;

/** 받은 inventory.reservation-pending 의 payload. shortages 는 production 이 쓰지 않아 받지 않는다. */
public record ReservationPending(Long productionId) {}
