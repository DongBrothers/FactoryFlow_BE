package com.factoryflow.mes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** VIN 발행 규칙 (docs/specs/production-completion.md "VIN 형식"). */
class VinTest {

    @Test
    void vinIsWmiProductCheckYearPlantAndWorkOrderSerial() {
        String vin = Vin.issue(1L, 12L, 2026);

        assertThat(vin).hasSize(17).startsWith("KMH00001").endsWith("TU000012");
        assertThat(vin.charAt(8)).isEqualTo(Vin.checkDigit(vin));
    }

    @Test
    void vinNeverUsesIOrQ() {
        for (long workOrderId = 1; workOrderId <= 300; workOrderId++) {
            assertThat(Vin.issue(workOrderId % 30 + 1, workOrderId, 2026))
                    .doesNotContain("I", "O", "Q");
        }
    }

    @Test
    void checkDigitFollowsTheNorthAmericanRule() {
        // 널리 쓰이는 검증 예: 1M8GDM9AXKP042788 의 체크 자리는 X, 11111111111111111 은 1
        assertThat(Vin.checkDigit("1M8GDM9AXKP042788")).isEqualTo('X');
        assertThat(Vin.checkDigit("11111111111111111")).isEqualTo('1');
    }

    @Test
    void modelYearCodeSkipsLettersNotUsedInVin() {
        assertThat(Vin.issue(1L, 1L, 2025).charAt(9)).isEqualTo('S');
        assertThat(Vin.issue(1L, 1L, 2026).charAt(9)).isEqualTo('T');
        assertThat(Vin.issue(1L, 1L, 2027).charAt(9)).isEqualTo('V');
        assertThat(Vin.issue(1L, 1L, 2031).charAt(9)).isEqualTo('1');
    }

    @Test
    void fitsTellsWhetherProductAndWorkOrderIdsFitTheirPlaces() {
        assertThat(Vin.fits(99_999L, 999_999L)).isTrue();
        assertThat(Vin.fits(100_000L, 1L)).isFalse();
        assertThat(Vin.fits(1L, 1_000_000L)).isFalse();
        assertThat(Vin.fits(-1L, 1L)).isFalse();
    }

    @Test
    void numbersThatDoNotFitAreRejected() {
        assertThatThrownBy(() -> Vin.issue(100_000L, 1L, 2026))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vin.issue(1L, 1_000_000L, 2026))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
