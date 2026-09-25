package com.dawnline.observability.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.DawnlineMetric;
import com.dawnline.observability.DawnlineMetric.Label;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 알림이 걸린 <strong>닫힌</strong> 카운터는 기동 때 0 으로 있다 (§9.1 「없는 시계열은 0 으로 보인다」, ADR-060 결정 3).
 *
 * <p>무엇이 대상인지는 사람이 적지 않는다 — 규칙 파일이 참조하는 카운터 가운데 카탈로그의 라벨 칸이 전부 닫힌 것, 그리고
 * §9.1 의 「emit 주체」가 이 서비스인 것. 새 알림이 닫힌 카운터에 걸리면 그 카운터를 내는 서비스의 IT 가 스스로 대상을
 * 늘린다. 열린 카운터는 대상이 아니다 — 그쪽은 규칙의 식이 부재를 다룬다({@code AlertRulesConsistencyTest}).
 *
 * <p>기동한 컨텍스트가 있는 IT 가 구현한다 — {@code InternalTokenSurfaceContract} 와 같은 모양이라 컨텍스트를 새로
 * 띄우지 않는다. 그 IT 의 태스크는 {@code docs/DESIGN.md} 와 규칙 파일을 입력으로 선언해야 한다(서비스 규약이 한다).
 */
public interface AlertedCountersContract {

    /**
     * @return 기동한 컨텍스트의 레지스트리
     */
    MeterRegistry meterRegistry();

    /**
     * @return §9.1 「emit 주체」 칸에서 이 서비스를 부르는 말({@code order} · {@code ops-api} …)
     */
    String emitter();

    @Test
    default void 알림이_걸린_닫힌_카운터는_기동_때_조합_전부가_있다() {
        List<DawnlineMetric> mine = MetricsTable.alertedClosedCountersEmittedBy(emitter());
        assertThat(mine).as("전제 — 이 서비스가 내는 알림 걸린 닫힌 카운터가 있다. 없으면 이 계약을 구현할 이유가 없다")
                .isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (DawnlineMetric metric : mine) {
            for (Map<String, String> combination : combinations(metric.labels())) {
                var search = meterRegistry().find(metric.meterName());
                for (var tag : combination.entrySet()) {
                    search = search.tag(tag.getKey(), tag.getValue());
                }
                if (search.counter() == null) {
                    missing.add(metric.name() + combination);
                }
            }
        }
        assertThat(missing).as("기동 때 없는 조합 — 첫 사건에서 1 로 태어나 increase() 가 그 첫 증가를 못 읽는다. "
                + "DawnlineMeters.preregister 로 등록한다").isEmpty();
    }

    private static List<Map<String, String>> combinations(List<Label> labels) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (Label label : labels) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : result) {
                for (String value : label.values()) {
                    Map<String, String> extended = new LinkedHashMap<>(partial);
                    extended.put(label.key(), value);
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }
}
