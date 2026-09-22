package com.dawnline.sim.driver;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * tracking 의 기사 스캔 API (DESIGN.md §5.4). 도구는 REST 로만 붙는다 (§5.6).
 *
 * <p>포트로 둔 이유는 {@code OrderClient} 와 같다 — 여정 로직(페이싱·재시도·집계)은 HTTP 와
 * 아무 상관이 없고, 서버 없이 시험할 수 있어야 한다.
 */
@FunctionalInterface
public interface ScanClient {

    /**
     * 스캔 하나를 보고한다. 예외를 던지지 않고 실패도 값으로 돌려준다.
     *
     * @param routeId 라우트 id
     * @param call    보낼 스캔
     */
    Response report(UUID routeId, ScanCall call);

    /**
     * 응답 하나.
     *
     * @param status      HTTP 상태. 연결 자체가 실패하면 {@code 0}
     * @param problemCode Problem Details 의 {@code code}. 없으면 {@code null}
     * @param failure     전송 실패 사유. 응답을 받았으면 {@code null}
     * @param outcomes    {@code orders[].outcome} 별 건수 —
     *                    {@code APPLIED}·{@code STALE}·{@code AFTER_CANCEL} (§5.4).
     *                    {@code STALE} 이 0 이 아닌 것은 정상이다: 중복 스캔을 상태 머신이
     *                    흡수했다는 뜻이고, 이 도구가 불변규칙 2 의 예외를 쓸 수 있는 근거다
     */
    record Response(int status, @Nullable String problemCode, @Nullable String failure,
            Map<String, Integer> outcomes) {

        public Response {
            outcomes = Map.copyOf(outcomes);
        }

        /** 응답을 받았다. */
        public static Response of(int status, @Nullable String problemCode, Map<String, Integer> outcomes) {
            return new Response(status, problemCode, null, outcomes);
        }

        /** 응답을 받지 못했다 (연결 거부·타임아웃). */
        public static Response transportFailure(String reason) {
            return new Response(0, null, reason, Map.of());
        }

        /** 스캔이 받아들여졌는가. */
        public boolean isAccepted() {
            return status == 200;
        }

        /**
         * 아직 그 라우트를 모른다 — 재시도해도 되는 404 다.
         *
         * <p>이 도구에서 404 가 나는 이유는 하나다: 같은 토픽을 <strong>두 컨슈머 그룹</strong>이
         * 따로 읽는다. tracking 이 {@code route.assigned} 를 배송으로 만들기 전에 이 도구가 먼저
         * 스캔을 쏘면 그 라우트는 아직 없다. 계약도 그렇게 적혀 있다 — 「아직
         * {@code route.assigned} 를 받지 못한 창일 수 있으므로 잠시 후 같은 요청을 다시 보내도
         * 된다」. 재시도에 상한이 있는 이유는 {@link DriverTrip} 참고.
         */
        public boolean isNotYetKnown() {
            return status == 404;
        }
    }
}
