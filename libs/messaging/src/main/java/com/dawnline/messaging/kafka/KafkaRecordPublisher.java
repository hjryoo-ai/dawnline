package com.dawnline.messaging.kafka;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.outbox.RecordPublisher;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaOperations;

/**
 * {@link RecordPublisher} 의 Spring Kafka 어댑터.
 *
 * <p>key·value 모두 {@code String} 이다. 봉투를 JSON 문자열로 직렬화해 그대로 보내기 때문이다.
 * Spring Kafka 의 {@code JsonSerializer} 를 쓰지 않는 이유: 그 직렬화기는 타입 헤더를 붙이고
 * 자체 매퍼 설정을 가져오는데, 우리 이벤트의 바이트는 {@code contracts/events} 가 정한다.
 * 계약이 정한 바이트를 프레임워크가 다시 손대게 두면 안 된다.
 *
 * <p>따라서 {@code spring.kafka.producer.value-serializer}(및 key)는 Boot 기본값인
 * {@code StringSerializer} 여야 한다. 이 전제가 깨지면 발행이 런타임에 실패한다.
 *
 * <h2>행을 쓴 트랜잭션의 트레이스로 보낸다 (§9.2)</h2>
 * 발행은 릴레이의 {@code @Scheduled} 폴링 안에서 일어나고, 그 폴링은 스스로 관측되어 자기 트레이스를 갖는다. 템플릿의
 * 관측(§9.2 가 켠다)은 헤더의 {@code traceparent} 를 <em>지우고</em> 현재 컨텍스트로 다시 쓴다 — 그대로 두면 모든
 * 이벤트가 릴레이 폴링의 트레이스로 나가고, 행에 저장해 둔 값은 한 번도 다음 서비스에 닿지 않는다. 그래서 보내기 전에
 * 저장된 헤더를 부모로 하는 관측 하나를 열고 그 안에서 보낸다: 템플릿의 스팬이 그 자식이 되어 같은 traceId 를 싣는다.
 *
 * <p>그 관측은 수신 문맥({@link ReceiverContext})이다 — outbox 표는 릴레이가 읽어 가는 큐이고, 부모를 운반체(행의 헤더)
 * 에서 꺼내는 것이 수신 쪽 처리기의 일이다. 저장된 {@code traceparent} 가 없으면(트레이싱을 끈 배포가 쓴 행) 새 트레이스로
 * 시작한다. 레지스트리가 없으면 {@code NOOP} 이고 아무것도 하지 않는다.
 */
public class KafkaRecordPublisher implements RecordPublisher {

    /** 관측 이름 — 스팬 이름은 {@code outbox relay}. 미터(타이머)도 이 이름으로 생긴다: {@code outbox_relay_seconds}. */
    static final String OBSERVATION = "outbox.relay";

    private final KafkaOperations<String, String> kafka;
    private final ObservationRegistry observations;

    /**
     * @param kafka        Spring Kafka 템플릿
     * @param observations 관측 레지스트리 — 없으면 {@link ObservationRegistry#NOOP}
     */
    public KafkaRecordPublisher(KafkaOperations<String, String> kafka, ObservationRegistry observations) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    @Override
    public CompletableFuture<Void> publish(String topic, String key, String value, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((name, headerValue) -> record.headers().add(name, EventHeaders.toBytes(headerValue)));
        // thenApply 로 결과를 버린다. 호출자는 "성공했는가" 만 알면 되고,
        // SendResult 를 노출하면 포트가 Spring Kafka 타입에 묶인다.
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>((carrier, name) -> carrier.get(name));
        context.setCarrier(headers);
        return Observation.createNotStarted(OBSERVATION, () -> context, observations)
                .contextualName("outbox relay")
                .observe(() -> kafka.send(record).thenApply(result -> null));
    }
}
