package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link MdcScope} 의 핵심 계약은 "빠져나올 때 이전 상태로 정확히 되돌린다"이다.
 * 예외 경로에서도 반드시 성립해야 스레드 재사용 시 로그 오염이 없다(DESIGN.md §9.3).
 */
class MdcScopeTest {

    @BeforeEach
    @AfterEach
    void MDC를비운다() {
        MDC.clear();
    }

    @Test
    void run_스코프안에서만값이보이고빠져나오면사라진다() {
        UUID orderId = UUID.randomUUID();

        MdcScope.builder()
                .service("order-service")
                .orderId(orderId)
                .run(() -> {
                    assertThat(MDC.get(MdcKeys.SERVICE)).isEqualTo("order-service");
                    assertThat(MDC.get(MdcKeys.ORDER_ID)).isEqualTo(orderId.toString());
                });

        assertThat(MDC.get(MdcKeys.SERVICE)).isNull();
        assertThat(MDC.get(MdcKeys.ORDER_ID)).isNull();
    }

    @Test
    void run_액션이예외를던져도MDC가복원된다() {
        MDC.put(MdcKeys.SERVICE, "dispatch-service");

        assertThatThrownBy(() -> MdcScope.builder()
                .waveId("wave-1")
                .service("intruder")
                .run(() -> {
                    throw new IllegalStateException("계획 실패");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(MdcKeys.WAVE_ID)).isNull();
        assertThat(MDC.get(MdcKeys.SERVICE)).isEqualTo("dispatch-service");
    }

    @Test
    void call_결과를돌려주고MDC를복원한다() {
        String result = MdcScope.builder()
                .eventId("evt-1")
                .call(() -> "처리:" + MDC.get(MdcKeys.EVENT_ID));

        assertThat(result).isEqualTo("처리:evt-1");
        assertThat(MDC.get(MdcKeys.EVENT_ID)).isNull();
    }

    @Test
    void close_중첩스코프는바깥값을덮어쓰지않고되돌린다() {
        MdcScope outer = MdcScope.builder().service("tracking-service").open();
        MdcScope inner = MdcScope.builder().service("tracking-service-batch").eventId("evt-2").open();

        assertThat(MDC.get(MdcKeys.SERVICE)).isEqualTo("tracking-service-batch");
        assertThat(MDC.get(MdcKeys.EVENT_ID)).isEqualTo("evt-2");

        inner.close();

        // 안쪽 스코프가 끝나면 바깥 값이 살아 있어야 한다 — 단순 remove 였다면 여기서 null 이 된다.
        assertThat(MDC.get(MdcKeys.SERVICE)).isEqualTo("tracking-service");
        assertThat(MDC.get(MdcKeys.EVENT_ID)).isNull();

        outer.close();
        assertThat(MDC.get(MdcKeys.SERVICE)).isNull();
    }

    @Test
    void close_두번불러도이전값을다시덮어쓰지않는다() {
        MdcScope scope = MdcScope.builder().routeId("route-1").open();
        scope.close();

        MDC.put(MdcKeys.ROUTE_ID, "route-2");
        scope.close();

        assertThat(MDC.get(MdcKeys.ROUTE_ID)).isEqualTo("route-2");
    }

    @Test
    void put_null값은아무것도넣지않는다() {
        MdcScope scope = MdcScope.builder().orderId(null).waveId("wave-9").open();

        assertThat(scope.keys()).containsExactly(MdcKeys.WAVE_ID);
        assertThat(MDC.get(MdcKeys.ORDER_ID)).isNull();

        scope.close();
    }

    @Test
    void clearManaged_관리대상만지우고traceId는남긴다() {
        MDC.put(MdcKeys.TRACE_ID, "0af7651916cd43dd8448eb211c80319c");
        MDC.put(MdcKeys.SPAN_ID, "b7ad6b7169203331");
        MDC.put(MdcKeys.SERVICE, "ops-api");
        MDC.put(MdcKeys.ORDER_ID, "order-1");

        MdcScope.clearManaged();

        assertThat(MDC.get(MdcKeys.SERVICE)).isNull();
        assertThat(MDC.get(MdcKeys.ORDER_ID)).isNull();
        // traceId/spanId 의 주인은 Micrometer Tracing 이다. 우리가 지우면 안 된다.
        assertThat(MDC.get(MdcKeys.TRACE_ID)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(MDC.get(MdcKeys.SPAN_ID)).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void put_임의키도넣을수있다() {
        MdcScope.builder()
                .put("camp", "CAMP-SEOUL-01")
                .run(() -> assertThat(MDC.get("camp")).isEqualTo("CAMP-SEOUL-01"));

        assertThat(MDC.get("camp")).isNull();
    }
}
