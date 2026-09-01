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
}
