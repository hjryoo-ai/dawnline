package com.dawnline.messaging.outbox;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 릴레이의 아웃바운드 포트 — 레코드 하나를 브로커로 보낸다.
 *
 * <p>{@code KafkaOperations} 대신 이 포트를 두는 이유는 두 가지다.
 *
 * <ol>
 *   <li><strong>테스트</strong>. {@link OutboxBatchPublisher} 의 핵심 로직은 부분 실패 처리와 순서 보장인데,
 *       그것을 검증하려면 "세 번째 전송만 실패" 같은 상황을 만들 수 있어야 한다. 이 포트는 인터페이스가
 *       메서드 하나라 손으로 만든 가짜로 충분하다.</li>
 *   <li><strong>경계</strong>. 배치 발행기는 outbox 행을 봉투로 되살리는 일에 집중하고, Kafka 프로듀서
 *       설정·직렬화기·헤더 API 는 어댑터({@code com.dawnline.messaging.kafka.KafkaRecordPublisher})가 안다.</li>
 * </ol>
 */
@FunctionalInterface
public interface RecordPublisher {

    /**
     * @param topic   대상 토픽 (§4.1)
     * @param key     파티션 키 (§4.5)
     * @param value   봉투 JSON
     * @param headers 레코드 헤더 (§4.2)
     * @return 브로커 확인이 끝나면 완료되는 future. 실패는 future 를 예외로 완료시켜 알린다.
     */
    CompletableFuture<Void> publish(String topic, String key, String value, Map<String, String> headers);
}
