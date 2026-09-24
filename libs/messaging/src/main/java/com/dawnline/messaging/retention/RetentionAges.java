package com.dawnline.messaging.retention;

import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code dawnline_retention_last_success_age_seconds{table}} — 표마다 마지막으로 <strong>성공한</strong> 정리 뒤로
 * 흐른 초 (DESIGN.md §9.1, ADR-058 결정 6).
 *
 * <h2>왜 필요한가 — 정리는 예외를 삼킨다</h2>
 * 정리 실패는 용량 문제지 정확성 문제가 아니라서 모든 정리 배치가 예외를 삼키고 다음 실행에 맡긴다. 그 결정은
 * 그대로 둔다. 그런데 조용히 실패하는 정리는 <em>멈춘 게이지의 정리판</em>이다 — 표는 자라고 아무도 모른다.
 * 이 게이지가 그 실패를 값으로 만든다.
 *
 * <h2>「한 일」이 아니라 「흐른 시간」을 잰다</h2>
 * 「지운 행 수」로 재면 정리가 죽은 순간 값이 마지막 성공치에서 멈추고, 멈춘 값은 정상과 구별되지 않는다(§9.1).
 * 이 값은 스크레이프마다 시계에서 계산하므로 정리가 멈추면 <strong>커진다</strong>. 성공한 적이 없으면
 * 그 표를 등록한 순간(기동)부터 센다 — {@code dawnline_kpi_refresh_age_seconds} 와 같은 모양이다.
 *
 * <h2>등록은 기동 때 한다</h2>
 * 정리기가 생성자에서 {@link #table(String)} 을 부른다. 라벨 값(표 이름)은 유한하므로 미리 등록할 수 있고,
 * 미리 등록하지 않으면 첫 성공 전까지 시계열이 없다 — 없는 시계열에는 알림이 울리지 않는다(§9.1 「짝」).
 * 라벨 값은 §7.1 보존 표의 첫 열이다.
 */
public class RetentionAges {

    private final MeterRegistry registry;
    private final Clock clock;
    private final ConcurrentMap<String, Table> tables = new ConcurrentHashMap<>();

    /**
     * @param registry 미터 레지스트리
     * @param clock    나이를 재는 시계 (불변규칙 12)
     */
    public RetentionAges(MeterRegistry registry, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 표 하나의 게이지를 등록하고 돌려준다. 같은 이름을 다시 부르면 같은 것을 돌려준다.
     *
     * @param name 표 이름 — §7.1 보존 표의 첫 열
     * @return 그 표의 성공 기록
     */
    public Table table(String name) {
        Objects.requireNonNull(name, "name");
        return tables.computeIfAbsent(name, this::register);
    }

    private Table register(String name) {
        Table table = new Table(name, clock);
        Gauge.builder(MessagingMetrics.RETENTION_LAST_SUCCESS_AGE, table, Table::ageSeconds)
                .description("그 표의 정리가 마지막으로 성공한 뒤로 흐른 초. 성공한 적이 없으면 기동부터 (ADR-058).")
                .tag(MessagingMetrics.TAG_TABLE, name)
                .register(registry);
        return table;
    }

    /** 표 하나의 마지막 성공. */
    public static final class Table {

        private final String name;
        private final Clock clock;
        private final AtomicReference<Instant> since;

        private Table(String name, Clock clock) {
            this.name = name;
            this.clock = clock;
            this.since = new AtomicReference<>(clock.instant());
        }

        /**
         * 이 표의 정리가 끝까지 돌았다. 한 실행의 배치 상한에 걸린 실행도 성공이다 — 앞으로 나아갔다.
         */
        public void succeeded() {
            since.set(clock.instant());
        }

        /**
         * 게이지 값.
         *
         * @return 마지막 성공(없으면 등록) 뒤로 흐른 초
         */
        public double ageSeconds() {
            return Duration.between(since.get(), clock.instant()).toMillis() / 1000.0;
        }

        /** 표 이름 — 게이지의 {@code table} 라벨. */
        public String name() {
            return name;
        }
    }
}
