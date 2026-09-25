package com.dawnline.observability;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * §9.1 표의 한 행 — 카탈로그 항목 (ADR-060 결정 1).
 *
 * <p>칸은 넷이다. 앞의 셋은 표의 첫 두 열과 코드의 이름이고, 넷째가 라벨 집합이다. 라벨 칸이 있어야 알림이 걸린
 * 카운터마다 「미리 등록하는가 / 알림 식이 부재를 다루는가」를 코드가 판정한다(결정 3).
 *
 * @param name      Prometheus 이름 — §9.1 의 첫 열 그대로
 * @param type      §9.1 의 둘째 열
 * @param meterName 코드가 등록하는 Micrometer 이름. Prometheus 레지스트리의 이름 규칙을 지나면 {@code name} 이 된다
 *                  ({@code DawnlineMetricsTest} 가 레지스트리로 확인한다)
 * @param help      Prometheus {@code HELP} 한 줄
 * @param labels    라벨 집합 — 등록할 때 키 집합이 정확히 이것이어야 한다
 */
public record DawnlineMetric(String name, Type type, String meterName, String help, List<Label> labels) {

    /** §9.1 의 타입 칸. */
    public enum Type {
        /** {@code Counter}. */
        COUNTER("counter"),
        /** {@code Gauge} — 관측 함수로 등록하고 값을 직접 set 하지 않는다. */
        GAUGE("gauge"),
        /** {@code Timer} + 퍼센타일 히스토그램 버킷. */
        HISTOGRAM("histogram");

        private final String cell;

        Type(String cell) {
            this.cell = cell;
        }

        /**
         * @return §9.1 의 타입 칸에 적히는 말
         */
        public String cell() {
            return cell;
        }
    }

    /**
     * 라벨 하나 — 값 목록이 있으면 <strong>닫혔고</strong>, 없으면 <strong>열렸다</strong>(§9.1 「라벨 칸의 문법」).
     *
     * @param key    라벨 키
     * @param values 닫힌 라벨의 값 전부. 열린 라벨이면 빈 목록
     */
    public record Label(String key, List<String> values) {

        /**
         * @param key    라벨 키
         * @param values 값 전부
         */
        public Label {
            Objects.requireNonNull(key, "key");
            values = List.copyOf(values);
            if (new HashSet<>(values).size() != values.size()) {
                throw new IllegalArgumentException("닫힌 라벨의 값이 겹친다: " + key + " " + values);
            }
        }

        /**
         * 열린 라벨 — 캠프 · 소비자 · 이벤트 타입처럼 코드가 값의 목록을 모른다. 미리 등록할 수 없다.
         *
         * @param key 라벨 키
         * @return 라벨
         */
        public static Label open(String key) {
            return new Label(key, List.of());
        }

        /**
         * 닫힌 라벨 — 코드가 값을 전부 안다. 목록 밖의 값은 등록에서 실패한다.
         *
         * @param key    라벨 키
         * @param values 값 전부(하나 이상)
         * @return 라벨
         */
        public static Label closed(String key, String... values) {
            if (values.length == 0) {
                throw new IllegalArgumentException("닫힌 라벨은 값이 하나 이상이다: " + key);
            }
            return new Label(key, List.of(values));
        }

        /**
         * @return 값 목록이 있는가
         */
        public boolean closed() {
            return !values.isEmpty();
        }
    }

    /**
     * @param name      Prometheus 이름
     * @param type      타입
     * @param meterName Micrometer 이름
     * @param help      설명
     * @param labels    라벨 집합
     */
    public DawnlineMetric {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(meterName, "meterName");
        Objects.requireNonNull(help, "help");
        labels = List.copyOf(labels);
        if (labelKeys(labels).size() != labels.size()) {
            throw new IllegalArgumentException("라벨 키가 겹친다: " + name);
        }
    }

    /**
     * @return 라벨 키 집합(표의 순서)
     */
    public Set<String> labelKeys() {
        return labelKeys(labels);
    }

    /**
     * @return 열린 라벨이 하나라도 있는가 — 있으면 미리 등록할 수 없고, 이 카운터에 건 알림은 식이 부재를 다룬다
     */
    public boolean hasOpenLabel() {
        return labels.stream().anyMatch(label -> !label.closed());
    }

    /**
     * @param key 라벨 키
     * @return 그 라벨
     * @throws IllegalArgumentException 이 항목에 그 키가 없을 때
     */
    public Label label(String key) {
        return labels.stream()
                .filter(label -> label.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " 에 라벨 " + key + " 가 없다"));
    }

    private static Set<String> labelKeys(List<Label> labels) {
        Set<String> keys = new LinkedHashSet<>();
        labels.forEach(label -> keys.add(label.key()));
        return keys;
    }
}
