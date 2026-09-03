package com.dawnline.order.application.port.out;

import com.dawnline.order.application.port.in.OrderAccepted;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code idempotency_keys} 접근 포트 (DESIGN.md §5.1, ADR-018·019).
 *
 * <p><strong>멱등의 진실은 이 테이블이다.</strong> Redis 는 헛일을 줄이는 장치일 뿐이고
 * (불변규칙 7), 같은 키의 동시 요청 중 하나만 성공한다는 보장은 여기 기본 키가 준다.
 */
public interface IdempotencyRecords {

    /**
     * 멱등 키로 완료 기록을 읽는다.
     *
     * @param key 멱등 키
     * @return 기록. 처음 보는 키거나 보존 기간이 지나 정리된 키면 빈 값
     */
    Optional<IdempotencyRecord> find(String key);

    /**
     * 완료 기록을 남긴다. <strong>주문 트랜잭션 안에서</strong> 호출한다 —
     * 주문이 롤백되면 이 기록도 사라져야 한다.
     *
     * <p>이미 그 키의 기록이 있으면 아무것도 하지 않고 {@code false} 를 돌려준다. 호출자는
     * 트랜잭션을 되돌리고 409 로 답해야 한다 — 그 사이 다른 요청이 같은 키를 끝냈다는 뜻이다.
     * 충돌 상대가 아직 커밋되지 않았다면 <em>그 트랜잭션이 끝날 때까지 기다린 뒤</em> 판정한다
     * (ADR-019 §2 의 측정).
     *
     * @param claim        멱등 키·지문·보관 기간
     * @param responseCode 저장할 HTTP 상태 코드
     * @param response     저장할 응답
     * @return 이 요청이 그 키의 주인이 되었으면 {@code true}
     */
    boolean complete(IdempotencyClaim claim, int responseCode, OrderAccepted response);

    /**
     * 보존 기간이 지난 기록을 최대 {@code batchSize} 건 지운다 (ADR-019).
     *
     * <p>한 번에 다 지우지 않는 이유는 {@code ProcessedEventCleaner} 와 같다 — 긴 삭제 트랜잭션은
     * 쓰기 경로와 경쟁하고, 실패하면 통째로 되돌아간다.
     *
     * @param now       기준 시각. {@code expires_at} 이 이보다 이른 행이 대상이다
     * @param batchSize 한 번에 지울 최대 건수
     * @return 실제로 지운 건수
     */
    int deleteExpired(Instant now, int batchSize);
}
