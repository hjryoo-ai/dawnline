package com.dawnline.messaging.idempotency;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code processed_events} 접근 포트 (DESIGN.md §4.4, §8.5).
 *
 * <p>인터페이스로 둔 이유: {@link IdempotentConsumer} 의 분기(최초/중복/거부)를 DB 없이
 * 단위 테스트하기 위해서다. 유일한 프로덕션 구현은 {@link JpaProcessedEventRepository} 다.
 */
public interface ProcessedEventRepository {

    /**
     * 이 이벤트를 이 소비자가 처리했다고 <strong>선점</strong>한다.
     *
     * <p>호출자의 트랜잭션 안에서 실행되어야 한다.
     *
     * @param eventId     봉투의 {@code eventId}
     * @param consumer    소비자 이름
     * @param processedAt 처리 시각
     * @return 이번 호출이 선점에 성공했으면 {@code true}, 이미 처리된 이벤트면 {@code false}
     */
    boolean markProcessed(UUID eventId, String consumer, Instant processedAt);

    /**
     * 이미 처리했는가. 진단·테스트용이며, 판정 자체는 {@link #markProcessed} 가 원자적으로 한다.
     *
     * @param eventId  봉투의 {@code eventId}
     * @param consumer 소비자 이름
     */
    boolean isProcessed(UUID eventId, String consumer);

    /**
     * 보존 기간이 지난 행을 <strong>최대 {@code limit} 개</strong> 지운다 (§4.4 보존 14일, §7.1).
     *
     * <p>한 번에 다 지우지 않고 상한을 두는 이유는 락 시간 때문이다. 이 테이블은 모든 리스너가
     * 트랜잭션 안에서 INSERT 하는 경로에 있어서, 정리 DELETE 가 큰 범위를 오래 잡고 있으면
     * 소비 경로 전체가 그동안 대기한다. 호출자({@code ProcessedEventCleaner})는 이 메서드를
     * <em>트랜잭션마다 한 번씩</em> 반복 호출해 배치 사이에 락을 놓는다.
     *
     * <p>오래된 행부터 지운다. 그래야 반복 호출이 매번 다른 행을 보고 진행이 보장된다.
     *
     * @param processedAtBefore 이 시각 이전에 처리 기록된 행이 대상
     * @param limit             이번 호출에서 지울 최대 행 수 (1 이상)
     * @return 실제로 삭제된 행 수. {@code limit} 보다 작으면 대상이 소진된 것이다.
     */
    int deleteProcessedBefore(Instant processedAtBefore, int limit);
}
