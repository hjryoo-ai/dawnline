package com.dawnline.order.application.port.out;

import com.dawnline.order.application.port.in.OrderAccepted;
import java.util.Optional;

/**
 * {@code idempotency_keys} 접근 포트 (DESIGN.md §5.1, ADR-018).
 *
 * <p><strong>멱등의 진실은 이 테이블이다.</strong> Redis 는 헛일을 줄이는 장치일 뿐이고
 * (불변규칙 7), 같은 키의 동시 요청 중 하나만 성공한다는 보장은 여기 기본 키가 준다.
 */
public interface IdempotencyRecords {

    /**
     * 멱등 키로 기록을 읽는다.
     *
     * @param key 멱등 키
     * @return 기록. 처음 보는 키면 빈 값
     */
    Optional<IdempotencyRecord> find(String key);

    /**
     * 완료 기록을 남긴다. <strong>주문 트랜잭션 안에서</strong> 호출한다 —
     * 주문이 롤백되면 이 기록도 사라져야 한다.
     *
     * <p>이미 {@link IdempotencyStatus#DONE} 인 행은 덮어쓰지 않는다. 그 경우 {@code false} 를
     * 돌려주며, 호출자는 트랜잭션을 되돌리고 409 로 답해야 한다 — 그 사이 다른 요청이 같은 키를
     * 끝냈다는 뜻이기 때문이다.
     *
     * @param claim        멱등 키·지문·보관 기간
     * @param responseCode 저장할 HTTP 상태 코드
     * @param response     저장할 응답
     * @return 이 요청이 그 키의 주인이 되었으면 {@code true}
     */
    boolean complete(IdempotencyClaim claim, int responseCode, OrderAccepted response);
}
