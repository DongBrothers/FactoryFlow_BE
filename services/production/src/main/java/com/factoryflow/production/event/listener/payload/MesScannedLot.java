package com.factoryflow.production.event.listener.payload;

/** 받은 mes.vehicle-completed 의 scannedLots 한 줄: 의장 공정에서 찍은 부품 LOT. */
public record MesScannedLot(Long partId, String lotNo) {}
