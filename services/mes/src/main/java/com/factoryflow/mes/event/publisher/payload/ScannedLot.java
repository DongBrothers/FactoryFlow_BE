package com.factoryflow.mes.event.publisher.payload;

/** 의장 공정에서 찍은 부품 LOT (mes.vehicle-completed 의 scannedLots 한 줄). */
public record ScannedLot(Long partId, String lotNo) {}
