package com.dawnline.common.archunit.samples.bad.application;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * 규칙 6 위반 표본 — 유스케이스가 {@code KafkaTemplate} 을 직접 부른다.
 *
 * <p>이 클래스가 <strong>컴파일된다는 사실 자체가</strong> 규칙 6이 필요한 이유다.
 * {@code libs/messaging} 이 Kafka 의존을 {@code api} 로 노출하므로 서비스의 유스케이스에서도
 * 똑같이 컴파일된다 — 사람이 리뷰에서 잡지 못하면 아무것도 막지 않는다.
 *
 * <p>여기서 일어나는 일: 도메인 변경은 자기 트랜잭션에서 커밋되고 발행은 브로커로 따로 나간다.
 * 둘 사이에서 프로세스가 죽으면 "주문은 저장됐는데 이벤트는 없는" 상태가 남고,
 * outbox 가 있었다면 불가능했을 상태다 (DESIGN.md §4.4).
 */
public class KafkaCallingUseCase {

    private final KafkaTemplate<String, String> kafka;

    /**
     * @param kafka 직접 주입받은 Kafka 템플릿 — 이것이 위반이다
     */
    public KafkaCallingUseCase(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /**
     * @param orderId 주문 id
     */
    public void placeOrder(String orderId) {
        kafka.send("dawnline.order.placed.v1", orderId, "{}");
    }
}
