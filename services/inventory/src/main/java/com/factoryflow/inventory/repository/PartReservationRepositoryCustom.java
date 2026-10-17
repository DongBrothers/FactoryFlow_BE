package com.factoryflow.inventory.repository;

import java.util.List;

public interface PartReservationRepositoryCustom {

    /** 기다리는 줄이 있는 부품마다 수량 합과 생산 건 수. 줄을 다 읽지 않고 DB 에서 부품별로 집계한다. */
    List<WaitingTotal> sumWaitingByPart();
}
