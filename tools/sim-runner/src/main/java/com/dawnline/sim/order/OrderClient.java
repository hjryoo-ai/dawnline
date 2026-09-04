package com.dawnline.sim.order;

import org.jspecify.annotations.Nullable;

/**
 * order-service 의 주문 접수 API (§5.6 — 도구는 REST 로만 붙는다).
 *
 * <p>포트로 둔 이유는 하나다: 시나리오 로직을 <strong>서버 없이</strong> 시험할 수 있어야 한다.
 * 200건을 보내는 페이싱·집계·판정은 HTTP 와 아무 상관이 없다.
 */
@FunctionalInterface
public interface OrderClient {

    /**
     * 주문 하나를 보낸다. 예외를 던지지 않고 실패도 값으로 돌려준다 — 시나리오는 한 건이
     * 실패해도 계속 가고, 무엇이 몇 건 실패했는지를 <em>끝에</em> 말해야 하기 때문이다.
     *
     * @param order          본문
     * @param idempotencyKey {@code Idempotency-Key} 헤더
     */
    Response place(GeneratedOrder order, String idempotencyKey);

    /**
     * 응답 하나.
     *
     * @param status      HTTP 상태. 연결 자체가 실패하면 {@code 0}
     * @param problemCode Problem Details 의 {@code code}. 없으면 {@code null}
     * @param failure     전송 실패 사유. 성공하면 {@code null}
     */
    record Response(int status, @Nullable String problemCode, @Nullable String failure) {

        /** 응답을 받았다. */
        public static Response of(int status, @Nullable String problemCode) {
            return new Response(status, problemCode, null);
        }

        /** 응답을 받지 못했다 (연결 거부·타임아웃). */
        public static Response transportFailure(String reason) {
            return new Response(0, null, reason);
        }

        /** 주문이 새로 접수되었는가. */
        public boolean isAccepted() {
            return status == 201;
        }
    }
}
