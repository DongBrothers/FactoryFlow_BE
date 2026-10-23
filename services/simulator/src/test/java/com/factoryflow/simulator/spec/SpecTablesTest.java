package com.factoryflow.simulator.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.simulator.domain.LineProcess;
import com.factoryflow.simulator.domain.TelemetryParameter;
import com.factoryflow.simulator.dto.response.InternalEquipmentResponse;
import com.factoryflow.simulator.service.EquipmentService;
import com.factoryflow.test.container.TestContainersConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * simulator 의 가상 설비와 센서 항목·정상 범위가 명세 표와 같은지 본다. mes 도 같은 표와 맞춰 본다 (서비스끼리 코드를 공유하지 않으므로 명세가 기준,
 * #179).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class SpecTablesTest {

    /** Gradle 은 서비스 폴더에서 테스트를 돌린다. */
    static final Path SPECS = Path.of("../../docs/specs");

    static final Pattern EQUIPMENT_ROW =
            Pattern.compile("^\\| ([A-Z]+-\\d{2}) \\| ([A-Z]+) \\| ([^|]+?) \\|$");

    /** 공정, 항목, 범위, 불량일 때 벗어나는 항목(비었으면 아님). */
    static final Pattern TELEMETRY_ROW =
            Pattern.compile(
                    "^\\| (PRESS|BODY|PAINT) \\| `(\\w+)` [^|]*\\| [^|]+? \\| ([\\d.]+) ~ ([\\d.]+) \\| ([^|]*?) ?\\|$");

    @Autowired EquipmentService equipmentService;

    @Test
    void virtualEquipmentMatchesTheSpecTable() throws IOException {
        List<String> spec =
                rows(SPECS.resolve("equipment-status.md"), EQUIPMENT_ROW).stream()
                        .map(m -> m.group(1) + " " + m.group(2))
                        .toList();
        List<String> fleet =
                equipmentService.findEquipments().stream()
                        .map(InternalEquipmentResponse::equipmentCode)
                        .map(code -> code + " " + code.substring(0, code.lastIndexOf('-')))
                        .toList();

        assertThat(spec).hasSize(10);
        assertThat(fleet).containsExactlyInAnyOrderElementsOf(spec);
        assertThat(equipmentService.findEquipments())
                .allSatisfy(
                        e -> assertThat(e.equipmentCode()).startsWith(e.process().name() + "-"));
    }

    @Test
    void telemetryParametersMatchTheSpecTable() throws IOException {
        List<String> spec =
                rows(SPECS.resolve("process-telemetry.md"), TELEMETRY_ROW).stream()
                        .map(
                                m ->
                                        String.join(
                                                " ",
                                                m.group(1),
                                                m.group(2),
                                                Double.toString(Double.parseDouble(m.group(3))),
                                                Double.toString(Double.parseDouble(m.group(4))),
                                                Boolean.toString(!m.group(5).isBlank())))
                        .toList();
        List<String> simulator =
                Arrays.stream(LineProcess.values())
                        .flatMap(
                                process ->
                                        TelemetryParameter.of(process).stream()
                                                .map(
                                                        p ->
                                                                String.join(
                                                                        " ",
                                                                        process.name(),
                                                                        p.key(),
                                                                        Double.toString(p.lower()),
                                                                        Double.toString(p.upper()),
                                                                        Boolean.toString(
                                                                                p.defectSignal()))))
                        .toList();

        assertThat(spec).hasSize(7);
        assertThat(simulator).containsExactlyElementsOf(spec);
    }

    private static List<Matcher> rows(Path file, Pattern row) throws IOException {
        return Files.readAllLines(file).stream()
                .map(row::matcher)
                .filter(Matcher::matches)
                .toList();
    }
}
