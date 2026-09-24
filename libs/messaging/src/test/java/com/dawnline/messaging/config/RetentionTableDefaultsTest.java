package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.docs.RetentionTable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * §7.1 보존 표 ↔ 이 모듈의 설정 기본값 (libs/messaging, ADR-058 결정 7).
 *
 * <p>기간은 설정에 살고 표는 그것을 비춘다. 대조는 양방향이다 — 표의 행 중 이 모듈의 키를 가진 것이 기본값과 같고,
 * 이 모듈의 보존 기간이 전부 표에 있다. 표의 모든 행이 어느 모듈의 대조에 걸려 있는지는 {@code libs/common} 의
 * {@code RetentionTableConsistencyTest} 가 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("§7.1 보존 표 ↔ 설정 기본값 (libs/messaging)")
class RetentionTableDefaultsTest {

    /** 이 모듈의 설정 접두사. */
    private static final String PREFIX = "dawnline.messaging.";

    private static final DawnlineMessagingProperties DEFAULTS = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("dawnline.messaging", DawnlineMessagingProperties.class);

    /** 이 모듈의 보존 기간 — 설정 키 → 기본값. */
    private static final Map<String, Duration> PERIODS = Map.of(
            "dawnline.messaging.outbox.retention", DEFAULTS.outbox().retention(),
            "dawnline.messaging.processed-events.retention-days", DEFAULTS.processedEvents().retention());

    @Test
    void 표의_행이_설정_기본값과_같다() {
        List<RetentionTable.Row> rows = RetentionTable.ownedBy(PREFIX);
        assertThat(rows).as("전제 — 이 모듈의 행이 표에 있다").isNotEmpty();

        for (RetentionTable.Row row : rows) {
            String key = row.key().orElseThrow();
            assertThat(PERIODS).as("%s — 표는 %s 다", row.label(), row.period())
                    .containsEntry(key, row.duration().orElseThrow());
        }
    }

    @Test
    void 이_모듈의_보존_기간이_전부_표에_있다() {
        assertThat(RetentionTable.ownedBy(PREFIX).stream().map(row -> row.key().orElseThrow()).toList())
                .as("설정에만 있는 기간은 표가 모르는 보존이다")
                .containsExactlyInAnyOrderElementsOf(PERIODS.keySet());
    }
}
