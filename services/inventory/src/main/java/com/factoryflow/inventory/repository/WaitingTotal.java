package com.factoryflow.inventory.repository;

/** 부품 하나의 기다리는(WAITING) 예약 줄 합계: 수량 합과 기다리는 생산 건 수. */
public record WaitingTotal(Long partId, Long waitingQty, Long waitingProductions) {}
