package com.dawnline.dispatch.config;

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
 * §7.1 보존 표 ↔ 이 모듈의 설정 기본값 (dispatch-service, ADR-059 결정 7).
 *
 * <p>기간은 설정에 살고 표는 그것을 비춘다. 대조는 양방향이다 — 표의 행 중 이 모듈의 키를 가진 것이 기본값과 같고,
 * 이 모듈의 보존 기간이 전부 표에 있다. 계획 계열 네 행은 키 하나({@code plans})를, 두 상한 행은 {@code cap} 을
 * 함께 쓴다 — 한 트랜잭션에서 함께 지우므로 표마다 다른 기간을 표현할 수 없다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("§7.1 보존 표 ↔ 설정 기본값 (dispatch-service)")
class RetentionTableDefaultsTest {

    /** 이 모듈의 설정 접두사. */
    private static final String PREFIX = "dawnline.dispatch.";

    private static final DispatchProperties DEFAULTS = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("dawnline.dispatch", DispatchProperties.class);

    /** 이 모듈의 보존 기간 — 설정 키 → 기본값. */
    private static final Map<String, Duration> PERIODS = Map.of(
            "dawnline.dispatch.retention.candidates", DEFAULTS.retention().candidates(),
            "dawnline.dispatch.retention.explanations", DEFAULTS.retention().explanations(),
            "dawnline.dispatch.retention.plans", DEFAULTS.retention().plans(),
            "dawnline.dispatch.retention.cap", DEFAULTS.retention().cap());

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
        // 여러 행이 한 키를 쓴다(계획 계열 넷 · 상한 둘) — 키의 집합으로 대조한다.
        assertThat(RetentionTable.ownedBy(PREFIX).stream().map(row -> row.key().orElseThrow()).distinct().toList())
                .as("설정에만 있는 기간은 표가 모르는 보존이다")
                .containsExactlyInAnyOrderElementsOf(PERIODS.keySet());
    }

    @Test
    void 계획_계열_네_표는_키_하나를_쓴다() {
        // 계열은 한 트랜잭션에서 자식부터 지운다(ADR-059 결정 6) — 표마다 기간이 다르면 그 문장은 거짓이다.
        assertThat(RetentionTable.ownedBy(PREFIX).stream()
                .filter(row -> row.key().orElseThrow().equals("dawnline.dispatch.retention.plans"))
                .map(RetentionTable.Row::table))
                .containsExactlyInAnyOrder("route_plans", "routes", "route_stops", "route_stop_orders");
    }

    @Test
    void 보존_레코드의_기간_칸은_전부_위_목록에_있다() {
        // 목록을 드는 방식이라, 레코드에 기간이 하나 늘면 여기서 먼저 깨진다(CLAUDE.md 「빼는 방식」).
        assertThat(PERIODS.keySet()).containsAll(
                RetentionTable.durationKeys(DispatchProperties.Retention.class, "dawnline.dispatch.retention"));
    }
}
