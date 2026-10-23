package com.factoryflow.inventory.event.listener.payload;

/** 받은 production.completed 의 scannedLots 한 줄: 의장 공정에서 찍은 부품 LOT. */
public record ScannedPartLot(Long partId, String lotNo) {}
