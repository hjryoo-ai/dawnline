package com.dawnline.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * MDC 값을 <strong>범위 안에서만</strong> 설정하고 빠져나올 때 이전 값을 그대로 되돌리는 스코프
 * (DESIGN.md §9.3).
 *
 * <p>HTTP 요청 경로는 {@link MdcFilter} 가 담당하고, 이 클래스는 그 밖의 모든 경로 —
 * Kafka 리스너, outbox 릴레이, 스케줄러, 시뮬레이터 스레드 — 를 위한 프로그램적 진입점이다.
 *
 * <h2>왜 "지우기"가 아니라 "되돌리기"인가</h2>
 * <p>단순히 끝에서 {@code MDC.remove} 를 하면 중첩 호출에서 바깥 스코프의 값까지 사라진다
 * (예: 릴레이가 {@code service} 를 넣은 상태에서 배치 항목마다 {@code eventId} 스코프를 여는 경우).
 * 그래서 열 때 이전 값을 기억했다가 닫을 때 정확히 복원한다. 이전 값이 없었으면 제거한다.
 *
 * <h2>사용법</h2>
 * <p>권장 형태는 {@link Builder#run(Runnable)} / {@link Builder#call(Supplier)} 다.
 * <pre>{@code
 * MdcScope.builder()
 *         .service("dispatch-service")
 *         .eventId(envelope.eventId())
 *         .waveId(payload.waveId())
 *         .run(() -> handle(envelope));
 * }</pre>
 *
 * <p>try-with-resources 도 가능하지만, 이 저장소의 컴파일 옵션이
 * {@code -Xlint:all -Werror} 이라 <em>리소스 변수를 본문에서 참조하지 않으면</em>
 * {@code [try]} 경고가 컴파일 오류가 된다. 그때는 메서드에 {@code @SuppressWarnings("try")}
 * 를 붙이거나 위의 {@code run}/{@code call} 을 쓴다.
 *
 * <h2>스레드</h2>
 * <p>MDC 는 스레드 로컬이다. 이 객체는 연 스레드에서만 닫아야 한다. 다른 스레드로 작업을
 * 넘길 때는 값을 명시적으로 넘겨 그쪽에서 새 스코프를 열어라. 가상 스레드에서도 동일하다.
 *
 * <p>개인정보는 넣지 않는다 — {@link MdcKeys} 의 정책 설명을 참고한다.
 */
public final class MdcScope implements AutoCloseable {

    /** 스코프를 열기 직전의 값. 값이 {@code null} 이면 "그 키는 없었다"는 뜻이다. */
    private final Map<String, @Nullable String> previous;

    private boolean closed;

    private MdcScope(Map<String, @Nullable String> previous) {
        this.previous = previous;
    }

    /** 새 스코프 빌더를 만든다. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 애플리케이션이 관리하는 MDC 키({@link MdcKeys#MANAGED})를 모두 지운다.
     *
     * <p>스레드 풀이 스레드를 재사용하기 때문에, 요청·메시지 처리의 마지막에 반드시 호출해
     * 다음 작업의 로그에 이전 작업의 {@code orderId} 가 묻어나지 않게 한다.
     * {@code traceId}/{@code spanId} 는 Micrometer Tracing 이 소유하므로 건드리지 않는다.
     */
    public static void clearManaged() {
        for (String key : MdcKeys.MANAGED) {
            MDC.remove(key);
        }
    }

    /**
     * 스코프를 닫고 이전 MDC 상태를 복원한다. 여러 번 불러도 안전하다.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    /** 이 스코프가 설정한 키들(디버깅·테스트용, 삽입 순서 유지). */
    public Set<String> keys() {
        return Collections.unmodifiableSet(previous.keySet());
    }

    /**
     * {@link MdcScope} 빌더. {@code null} 값은 조용히 무시하므로
     * {@code .orderId(maybeNull)} 을 그대로 호출해도 된다.
     */
    public static final class Builder {

        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder() {
        }

        /** {@link MdcKeys#SERVICE} */
        public Builder service(@Nullable Object value) {
            return put(MdcKeys.SERVICE, value);
        }

        /** {@link MdcKeys#EVENT_ID} */
        public Builder eventId(@Nullable Object value) {
            return put(MdcKeys.EVENT_ID, value);
        }

        /** {@link MdcKeys#ORDER_ID} */
        public Builder orderId(@Nullable Object value) {
            return put(MdcKeys.ORDER_ID, value);
        }

        /** {@link MdcKeys#WAVE_ID} */
        public Builder waveId(@Nullable Object value) {
            return put(MdcKeys.WAVE_ID, value);
        }

        /** {@link MdcKeys#ROUTE_ID} */
        public Builder routeId(@Nullable Object value) {
            return put(MdcKeys.ROUTE_ID, value);
        }

        /**
         * 임의의 키를 넣는다. 값은 {@code toString()} 으로 문자열화한다.
         *
         * <p>개인정보(전체 주소·수령인·연락처)를 넣지 않는다. 위치는 우편번호나 geohash 만
         * 허용한다(DESIGN.md §9.3).
         *
         * @param value {@code null} 이면 아무것도 하지 않는다
         */
        public Builder put(String key, @Nullable Object value) {
            Objects.requireNonNull(key, "key");
            if (value != null) {
                values.put(key, value.toString());
            }
            return this;
        }

        /**
         * 스코프를 연다. 반환된 객체를 반드시 {@link MdcScope#close()} 해야 한다.
         * 가능하면 {@link #run(Runnable)} / {@link #call(Supplier)} 를 써라.
         */
        public MdcScope open() {
            Map<String, @Nullable String> previous = new LinkedHashMap<>(values.size());
            values.forEach((key, value) -> {
                previous.put(key, MDC.get(key));
                MDC.put(key, value);
            });
            return new MdcScope(previous);
        }

        /** 스코프 안에서 {@code action} 을 실행한다. 예외가 나도 MDC 는 복원된다. */
        public void run(Runnable action) {
            Objects.requireNonNull(action, "action");
            MdcScope scope = open();
            try {
                action.run();
            } finally {
                scope.close();
            }
        }

        /** 스코프 안에서 {@code action} 을 실행하고 결과를 돌려준다. 예외가 나도 MDC 는 복원된다. */
        public <T> T call(Supplier<T> action) {
            Objects.requireNonNull(action, "action");
            MdcScope scope = open();
            try {
                return action.get();
            } finally {
                scope.close();
            }
        }
    }
}
