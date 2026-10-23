package com.factoryflow.production.event.publisher.payload;

/** production.completed 의 scannedLots 한 줄: 의장 공정에서 찍은 부품 LOT (mes 에서 받은 그대로). */
public record CompletedPartLot(Long partId, String lotNo) {}
