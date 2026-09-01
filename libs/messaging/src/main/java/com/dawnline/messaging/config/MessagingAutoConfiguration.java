package com.dawnline.messaging.config;

import com.dawnline.common.Ids;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.TraceparentSupplier;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * libs/messaging 의 공통 배선 — 어떤 서비스에서도 켜진다.
 *
 * <p>여기서 만드는 것은 인프라(JPA·Kafka)에 의존하지 않는 것들뿐이다.
 * JPA 가 필요한 것은 {@link MessagingJpaAutoConfiguration}, Kafka 가 필요한 것은
 * {@link MessagingKafkaAutoConfiguration} 이 만든다.
 *
 * <p>모든 빈에 {@code @ConditionalOnMissingBean} 이 붙어 있어 서비스가 자기 것으로 갈아끼울 수 있다.
 */
@AutoConfiguration
@EnableConfigurationProperties(DawnlineMessagingProperties.class)
public class MessagingAutoConfiguration {

    /** {@code spring.application.name} 도 {@code dawnline.messaging.producer} 도 없을 때의 안내. */
    private static final String MISSING_PRODUCER =
            "발행자 이름을 알 수 없습니다. dawnline.messaging.producer 또는 spring.application.name 을 "
                    + "소문자 kebab-case(예: order-service)로 설정하세요. "
                    + "이 값은 이벤트 봉투의 producer 필드(envelope.v1.schema.json)가 됩니다.";

    /**
     * 이벤트 전용 JSON 코덱.
     *
     * <p>애플리케이션의 {@code ObjectMapper} 를 재사용하지 않는 이유는 {@link EventJson} Javadoc 참고 —
     * 이벤트 JSON 은 서비스 간 계약이고, {@code spring.jackson.*} 변경에 흔들리면 안 된다.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventJson dawnlineEventJson() {
        return EventJson.standard();
    }

    /**
     * 트레이스 컨텍스트 제공자의 기본값(항상 비어 있음).
     *
     * <p>{@code libs/observability} 가 실제 구현을 빈으로 등록하면 그쪽이 이긴다.
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceparentSupplier dawnlineTraceparentSupplier() {
        return TraceparentSupplier.NONE;
    }

    /**
     * UUIDv7 생성기 (CLAUDE.md 불변규칙 10·12).
     *
     * <p>{@code Clock} 은 이미 등록된 빈이 있으면 그것을 쓴다. 테스트에서 고정 {@code Clock} 빈을 넣으면
     * outbox 의 {@code occurredAt} 과 eventId 의 시간 성분이 동시에 고정된다.
     *
     * @param clock 시각 출처 (없으면 시스템 UTC)
     */
    @Bean
    @ConditionalOnMissingBean
    public Ids dawnlineIds(ObjectProvider<Clock> clock) {
        return new Ids(clock.getIfAvailable(Clock::systemUTC), RandomGenerator.getDefault());
    }

    /**
     * 봉투의 {@code producer} 값을 정한다.
     *
     * @param properties  {@code dawnline.messaging.*}
     * @param environment {@code spring.application.name} 조회용
     * @return 발행자 이름
     * @throws IllegalStateException 둘 다 없을 때
     */
    static String resolveProducer(DawnlineMessagingProperties properties, Environment environment) {
        String configured = properties.producer();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String applicationName = environment.getProperty("spring.application.name");
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException(MISSING_PRODUCER);
        }
        return applicationName;
    }
}
