package com.factoryflow.common.event;

import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/** 테스트에서 코드를 @Transactional 트랜잭션 안에서 실행한다. */
public class InTransaction {

    @Transactional
    public <T> T call(Supplier<T> work) {
        return work.get();
    }

    @Transactional
    public void run(Runnable work) {
        work.run();
    }
}
