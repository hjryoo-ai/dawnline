package com.dawnline.messaging.outbox;

import java.time.Instant;
import java.util.List;

/**
 * {@code outbox_events} 접근 포트.
 *
 * <p>인터페이스로 둔 이유: 릴레이 로직(배치·부분 실패 처리·메트릭)을 DB 없이 단위 테스트하기 위해서다.
 * 유일한 프로덕션 구현은 {@link JpaOutboxRepository} 다.
 */
public interface OutboxRepository {

    /**
     * 도메인 변경과 같은 트랜잭션에서 미발행 행을 INSERT 한다 (CLAUDE.md 불변규칙 1).
     *
     * @param event 저장할 행
     */
    void append(OutboxEvent event);

    /**
     * 미발행 행을 오래된 순으로 잠그고 가져온다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 를 쓴다. 릴레이 인스턴스가 여러 개여도 같은 행을
     * 두 번 발행하지 않고, 느린 인스턴스가 다른 인스턴스를 막지도 않는다 (§4.4).
     * {@code SKIP LOCKED} 때문에 반환 행 수가 {@code batchSize} 보다 적을 수 있다.
     *
     * @param batchSize 최대 행 수
     * @return 잠긴 행들. 호출 트랜잭션이 끝날 때까지 잠금이 유지된다.
     */
    List<OutboxEvent> lockUnpublishedBatch(int batchSize);

    /**
     * 미발행 행 수. {@code dawnline_outbox_unpublished} 게이지의 값 (§9.1).
     *
     * <p>격리 행({@code failed_at IS NOT NULL})은 세지 않는다 — {@link #countFailed()} 와
     * 겹치면 같은 행이 두 게이지에 동시에 잡혀 대시보드가 모순된다.
     */
    long countUnpublished();

    /**
     * 격리된 행 수. {@code dawnline_outbox_failed} 게이지의 값 (§9.1, §4.6).
     *
     * <p>0 이 아니면 사람이 봐야 한다 — 알림 규칙이 걸려 있다(§9.4, RB-05).
     */
    long countFailed();

    /**
     * 가장 오래된 미발행 행이 만들어진 뒤 흐른 시간(초). 미발행이 없으면 0.
     *
     * <p>애플리케이션 시계가 아니라 <strong>DB 시계</strong>로 계산한다. 릴레이 인스턴스가 여러 대일 때
     * 인스턴스별 시계 오차가 그대로 지표 노이즈가 되는 것을 피하기 위해서다.
     * {@code dawnline_outbox_lag_seconds} 게이지의 값 (§9.1).
     */
    double unpublishedLagSeconds();

    /**
     * 발행된 지 오래된 행을 지운다 (§7.1 — 파티셔닝 대신 삭제).
     *
     * @param publishedBefore 이 시각 이전에 발행된 행을 삭제
     * @return 삭제된 행 수
     */
    int deletePublishedBefore(Instant publishedBefore);
}
