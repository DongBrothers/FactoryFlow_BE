package com.factoryflow.mes.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.TelemetryParameter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * mes 의 설비 기본 데이터와 센서 항목·정상 범위가 명세 표와 같은지 본다. simulator 도 같은 표와 맞춰 본다 (서비스끼리 코드를 공유하지 않으므로 명세가 기준,
 * #179).
 */
class SpecTablesTest {

    /** Gradle 은 서비스 폴더에서 테스트를 돌린다. */
    static final Path SPECS = Path.of("../../docs/specs");

    static final Pattern EQUIPMENT_ROW =
            Pattern.compile("^\\| ([A-Z]+-\\d{2}) \\| ([A-Z]+) \\| ([^|]+?) \\|$");
    static final Pattern TELEMETRY_ROW =
            Pattern.compile(
                    "^\\| (PRESS|BODY|PAINT) \\| `(\\w+)` [^|]*\\| ([^|]+?) \\| ([\\d.]+) ~ ([\\d.]+) \\|.*$");
    static final Pattern SEED_ROW = Pattern.compile("\\('([A-Z]+-\\d{2})', '([^']+)', '([A-Z]+)'");

    @Test
    void seededEquipmentMatchesTheSpecTable() throws IOException {
        List<String> spec =
                rows(SPECS.resolve("equipment-status.md"), EQUIPMENT_ROW).stream()
                        .map(m -> m.group(1) + " " + m.group(2) + " " + m.group(3))
                        .toList();
        String seed =
                new ClassPathResource("db/migration/V2__seed_equipment.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        Matcher m = SEED_ROW.matcher(seed);
        List<String> seeded = new ArrayList<>();
        while (m.find()) {
            seeded.add(m.group(1) + " " + m.group(3) + " " + m.group(2));
        }

        assertThat(spec).hasSize(10);
        assertThat(seeded).containsExactlyElementsOf(spec);
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
                                                m.group(3),
                                                number(m.group(4)),
                                                number(m.group(5))))
                        .toList();
        List<String> mes =
                Arrays.stream(ProductionProcess.values())
                        .flatMap(
                                process ->
                                        TelemetryParameter.of(process).stream()
                                                .map(
                                                        p ->
                                                                String.join(
                                                                        " ",
                                                                        process.name(),
                                                                        p.key(),
                                                                        p.unit(),
                                                                        number(p.lower()),
                                                                        number(p.upper()))))
                        .toList();

        assertThat(spec).hasSize(7);
        assertThat(mes).containsExactlyElementsOf(spec);
    }

    private static List<Matcher> rows(Path file, Pattern row) throws IOException {
        return Files.readAllLines(file).stream()
                .map(row::matcher)
                .filter(Matcher::matches)
                .toList();
    }

    private static String number(String text) {
        return number(Double.parseDouble(text));
    }

    private static String number(double value) {
        return Double.toString(value);
    }
}
