package com.factoryflow.purchase.repository;

/** 부품 하나의 발주 중(ORDERED) 수량 합. */
public record OrderedTotal(Long partId, Long qty) {}
