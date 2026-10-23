package com.factoryflow.simulator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 난수 생성기는 JRE 이미지 구성과 상관없이 있는 java.base 구현을 쓴다 (jdk.random 은 JRE 이미지에 없을 수 있다). */
class SimulatorConfigTest {

    @Test
    void randomGeneratorComesFromJavaBase() {
        Module module = new SimulatorConfig().randomGenerator().getClass().getModule();

        assertThat(module.getName()).isEqualTo("java.base");
    }
}
