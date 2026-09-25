package com.dawnline.observability;

import com.dawnline.observability.DawnlineMetric.Label;
import com.dawnline.observability.DawnlineMetric.Type;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

/**
 * 미터 등록의 유일한 자리 (ADR-060 결정 2, ArchUnit 규칙 11).
 *
 * <p>등록할 때 카탈로그({@link DawnlineMetrics})와 대조한다 — 틀린 자리는 등록이고, 그것이 원인 옆이다.
 * <ul>
 *   <li><strong>타입</strong>이 항목과 다르면 실패한다.</li>
 *   <li><strong>라벨 키 집합</strong>이 항목과 다르면 실패한다. Prometheus 레지스트리도 같은 이름의 키 집합이 다르면
 *       거부하지만(ADR-022) 그것은 둘째 등록에서다 — 여기서는 첫 등록에서 실패한다.</li>
 *   <li><strong>닫힌 라벨의 값</strong>이 목록 밖이면 실패한다. 「닫혔다」는 「코드가 값을 전부 안다」는 주장이다.</li>
 *   <li>{@code histogram} 은 {@code publishPercentileHistogram()} — 타입 칸이 곧 버킷이다. 속성 파일의 키가 미터 이름과
 *       맞는지에 기대던 때는 맞지 않아 버킷이 없었다(ADR-060 맥락 1).</li>
 *   <li><strong>게이지는 {@code strongReference(true)}</strong>. Micrometer 는 상태 객체를 약한 참조로 들고, 대상이 GC 되면
 *       게이지가 조용히 {@code NaN} 을 낸다 — 이 저장소에서 {@code NaN} 은 「모름」의 값이라 그 결함이 「모름」 검사를
 *       대상 없이 통과시켰다(§13 축 10). 등록하는 쪽은 전부 싱글턴이라 강한 참조가 새게 하는 것은 없다.</li>
 * </ul>
 *
 * <p>태그는 {@code key, value, key, value, …} 로 준다 — Micrometer 의 {@code Tags.of(String...)} 와 같은 모양이다.
 */
public final class DawnlineMeters {

    private DawnlineMeters() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }

    /**
     * 카운터를 등록하거나 이미 있는 것을 돌려준다.
     *
     * @param registry 레지스트리
     * @param metric   카탈로그 항목 — {@code counter}
     * @param tags     {@code key, value, …}
     * @return 카운터
     */
    public static Counter counter(MeterRegistry registry, DawnlineMetric metric, String... tags) {
        Tags checked = checked(metric, Type.COUNTER, tags);
        return Counter.builder(metric.meterName())
                .description(metric.help())
                .tags(checked)
                .register(registry);
    }

    /**
     * 타이머를 버킷과 함께 등록하거나 이미 있는 것을 돌려준다.
     *
     * @param registry 레지스트리
     * @param metric   카탈로그 항목 — {@code histogram}
     * @param tags     {@code key, value, …}
     * @return 타이머
     */
    public static Timer timer(MeterRegistry registry, DawnlineMetric metric, String... tags) {
        Tags checked = checked(metric, Type.HISTOGRAM, tags);
        return Timer.builder(metric.meterName())
                .description(metric.help())
                .tags(checked)
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 게이지를 <strong>강한 참조</strong>로 등록한다. 같은 이름 · 태그가 이미 있으면 레지스트리가 기존 것을 돌려주고 새 상태
     * 객체는 쓰이지 않는다 — 부르는 쪽이 (이름, 태그)마다 상태 객체를 하나씩 든다.
     *
     * @param registry 레지스트리
     * @param metric   카탈로그 항목 — {@code gauge}
     * @param state    상태 객체. 레지스트리가 사는 동안 잡힌다
     * @param value    상태에서 값을 읽는 함수. 모르면 {@code NaN}
     * @param tags     {@code key, value, …}
     * @param <T>      상태 타입
     * @return 게이지
     */
    public static <T> Gauge gauge(MeterRegistry registry, DawnlineMetric metric, T state,
            ToDoubleFunction<T> value, String... tags) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(value, "value");
        Tags checked = checked(metric, Type.GAUGE, tags);
        return Gauge.builder(metric.meterName(), state, value)
                .description(metric.help())
                .tags(checked)
                .strongReference(true)
                .register(registry);
    }

    /**
     * 닫힌 라벨의 값 조합 전부를 0 으로 등록한다 — 알림이 걸린 카운터는 기동 때 부른다(§9.1 「없는 시계열은 0 으로 보인다」,
     * ADR-060 결정 3). 처음 셀 때 만들면 시계열이 1 로 태어나고 {@code increase()} 는 그 첫 증가를 읽지 못한다.
     *
     * <p>{@code fixed} 로 준 라벨은 그 값으로 고정하고, 나머지 라벨은 <strong>전부 닫혀 있어야 한다</strong> — 열린 라벨은
     * 조합을 만들 수 없다. 그때는 알림 식이 부재를 다룬다.
     *
     * @param registry 레지스트리
     * @param metric   카탈로그 항목 — {@code counter}
     * @param fixed    고정할 라벨 {@code key, value, …}
     * @return 등록한 조합의 수
     */
    public static int preregister(MeterRegistry registry, DawnlineMetric metric, String... fixed) {
        Map<String, String> fixedTags = pairs(metric, fixed);
        List<Map<String, String>> combinations = new ArrayList<>();
        combinations.add(new LinkedHashMap<>());
        for (Label label : metric.labels()) {
            List<String> values;
            if (fixedTags.containsKey(label.key())) {
                values = List.of(fixedTags.get(label.key()));
            } else if (label.closed()) {
                values = label.values();
            } else {
                throw new IllegalArgumentException(metric.name() + " 의 라벨 " + label.key()
                        + " 는 열려 있어 미리 등록할 수 없다 — 값을 고정하거나 알림 식이 부재를 다룬다(ADR-060 결정 3)");
            }
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : combinations) {
                for (String value : values) {
                    Map<String, String> extended = new LinkedHashMap<>(partial);
                    extended.put(label.key(), value);
                    next.add(extended);
                }
            }
            combinations = next;
        }
        for (Map<String, String> combination : combinations) {
            List<String> flat = new ArrayList<>();
            combination.forEach((key, value) -> {
                flat.add(key);
                flat.add(value);
            });
            counter(registry, metric, flat.toArray(String[]::new));
        }
        return combinations.size();
    }

    private static Tags checked(DawnlineMetric metric, Type expected, String... tags) {
        Objects.requireNonNull(metric, "metric");
        if (metric.type() != expected) {
            throw new IllegalArgumentException(metric.name() + " 는 " + metric.type().cell() + " 다 — "
                    + expected.cell() + " 로 등록할 수 없다(§9.1 타입 칸)");
        }
        Map<String, String> given = pairs(metric, tags);
        if (!given.keySet().equals(metric.labelKeys())) {
            throw new IllegalArgumentException(metric.name() + " 의 라벨 키는 " + new TreeSet<>(metric.labelKeys())
                    + " 인데 " + new TreeSet<>(given.keySet()) + " 로 등록하려 했다(§9.1 라벨 칸)");
        }
        List<Tag> result = new ArrayList<>();
        for (Label label : metric.labels()) {
            String value = given.get(label.key());
            if (label.closed() && !label.values().contains(value)) {
                throw new IllegalArgumentException(metric.name() + " 의 닫힌 라벨 " + label.key() + " 에 목록 밖의 값 \""
                        + value + "\" — 목록은 " + label.values() + " 다(§9.1 라벨 칸, ADR-060 결정 2)");
            }
            result.add(Tag.of(label.key(), value));
        }
        return Tags.of(result);
    }

    private static Map<String, String> pairs(DawnlineMetric metric, String... tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException(metric.name() + " 의 태그는 key, value 쌍이다: " + List.of(tags));
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        for (int i = 0; i < tags.length; i += 2) {
            String key = Objects.requireNonNull(tags[i], "tag key");
            String value = Objects.requireNonNull(tags[i + 1], () -> "tag value of " + key);
            if (pairs.put(key, value) != null) {
                throw new IllegalArgumentException(metric.name() + " 의 태그 키가 겹친다: " + key);
            }
        }
        return pairs;
    }
}
