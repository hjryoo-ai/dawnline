package com.dawnline.messaging.config;

import com.dawnline.common.Ids;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.TraceparentSupplier;
import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.messaging.tracing.TracerTraceparentSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
@EnableConfigurationProperties({DawnlineMessagingProperties.class, DawnlineClockProperties.class})
public class MessagingAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    /** PostgreSQL {@code TIMESTAMPTZ} 의 해상도. 이보다 정밀한 값은 저장할 때 잘린다. */
    private static final Duration STORAGE_RESOLUTION = Duration.ofNanos(1_000);

    /** {@code spring.application.name} 도 {@code dawnline.messaging.producer} 도 없을 때의 안내. */
    private static final String MISSING_PRODUCER =
            "발행자 이름을 알 수 없습니다. dawnline.messaging.producer 또는 spring.application.name 을 "
                    + "소문자 kebab-case(예: order-service)로 설정하세요. "
                    + "이 값은 이벤트 봉투의 producer 필드(envelope.v1.schema.json)가 됩니다.";

    /**
     * DB 가 담을 수 있는 정밀도까지만 내려주는 시각 출처 (CLAUDE.md 불변규칙 12).
     *
     * <h2>왜 {@code Clock.systemUTC()} 를 그대로 쓰지 않는가</h2>
     * PostgreSQL 의 {@code TIMESTAMPTZ} 는 <strong>마이크로초</strong>까지만 저장한다.
     * 그런데 {@code Clock.systemUTC()} 의 해상도는 플랫폼에 달려 있어서, Linux 에서는 나노초까지
     * 나오고 macOS 에서는 마이크로초에서 끊긴다. 나노초가 섞이면 이런 일이 생긴다.
     *
     * <ul>
     *   <li>{@code POST /orders} 응답의 {@code placedAt} 과 {@code GET /orders/{id}} 의
     *       {@code placedAt} 이 다르다 — 같은 사실에 두 개의 표기가 생긴다.</li>
     *   <li>{@code idempotency_keys.response_body} 에 저장한 응답(나노초)과 DB 에서 다시 읽은
     *       주문(마이크로초)이 어긋난다. 멱등 재생이 "그때 준 답" 을 그대로 주지 못한다.</li>
     *   <li>테스트가 <strong>개발 기계에서는 통과하고 CI 에서만 깨진다.</strong>
     *       실제로 그렇게 발견됐다.</li>
     * </ul>
     *
     * <p>그래서 저장소가 담을 수 있는 정밀도로 잘라서 내려준다. 잘린 값은 왕복해도 그대로다.
     * 서비스가 자기 {@code Clock} 빈을 등록하면 그쪽이 이긴다(테스트의 고정 시계 등) — 그때는
     * 같은 이유로 마이크로초 이하를 넣지 않는 것이 낫다.
     *
     * <h2>오프셋 (ADR-066)</h2>
     * {@code dawnline.clock.offset} 이 0 이 아니면 벽시계에 그만큼 더한다 — 시뮬레이션은 스케줄이 아니라 시계를 옮긴다.
     * 프로필 {@code sim} 밖에서는 기동하지 않는다({@link ClockAnnouncement#requireAllowed}).
     *
     * @param clock       {@code dawnline.clock.*}
     * @param environment 프로필 조회
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock dawnlineClock(DawnlineClockProperties clock, Environment environment) {
        return storagePrecisionClock(ClockAnnouncement.requireAllowed(clock, environment));
    }

    /**
     * 기동 거부 · 유효 시각 한 줄 · 오프셋 게이지 (ADR-066 결정 2 · 3). 시계 빈이 갈아끼워져도 뜬다 — 검사가 빠지지 않게.
     *
     * @param properties  {@code dawnline.messaging.*} — {@code service} 태그
     * @param clock       {@code dawnline.clock.*}
     * @param clocks      이 컨텍스트의 시계 빈 (없으면 저장 정밀도의 시스템 UTC)
     * @param environment 프로필 · 애플리케이션 이름
     * @param meters      게이지 레지스트리 (없으면 내지 않는다)
     * @return 기동 때 한 번 말하는 빈
     */
    @Bean
    public ClockAnnouncement dawnlineClockAnnouncement(DawnlineMessagingProperties properties,
            DawnlineClockProperties clock, ObjectProvider<Clock> clocks, Environment environment,
            ObjectProvider<MeterRegistry> meters) {
        return new ClockAnnouncement(clock, clocks.getIfAvailable(MessagingAutoConfiguration::storagePrecisionClock),
                environment, meters.getIfAvailable(), serviceTag(properties, environment));
    }

    /**
     * 게이지의 {@code service} 값 — {@link #resolveProducer} 와 같은 순서지만 없다고 기동을 막지 않는다. 발행자 이름은 outbox 가
     * 요구하는 것이고, 시계는 outbox 없는 구성에도 있다.
     */
    private static String serviceTag(DawnlineMessagingProperties properties, Environment environment) {
        String configured = properties.producer();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return environment.getProperty("spring.application.name", "unknown");
    }

    /**
     * {@link #dawnlineClock(DawnlineClockProperties, Environment)} 의 오프셋 0 과 같은 시계를 빈 없이 만든다.
     *
     * <p>{@code ObjectProvider<Clock>} 의 폴백으로 쓴다. 폴백이 {@code Clock.systemUTC()} 면
     * 빈이 없는 구성에서 나노초가 다시 들어오고, 그것은 이 클래스가 고치려던 바로 그 문제다.
     */
    public static Clock storagePrecisionClock() {
        return storagePrecisionClock(Duration.ZERO);
    }

    /**
     * 벽시계에 오프셋을 더하고 저장 정밀도로 자른다 — 자르기가 바깥이라 오프셋에 마이크로초 아래가 있어도 값에는 없다.
     *
     * @param offset 더할 기간 (ADR-066)
     * @return 시계
     */
    public static Clock storagePrecisionClock(Duration offset) {
        Clock wall = Clock.systemUTC();
        return Clock.tick(offset.isZero() ? wall : Clock.offset(wall, offset), STORAGE_RESOLUTION);
    }

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
     * 트레이스 컨텍스트 제공자 — 트레이싱 스택이 있으면 현재 스팬에서, 없으면 비어 있다(§9.2).
     *
     * <p><strong>7-2 까지 이 자리에는 {@code NONE} 하나뿐이었다.</strong> 문서는 「{@code libs/observability} 가
     * 실제 구현을 등록하면 그쪽이 이긴다」고 했지만 그 등록은 한 번도 없었고, ADR-060 이 의존 방향을 뒤집은 뒤로는
     * 있을 수도 없었다({@code libs/messaging} → {@code libs/observability}). 기본값이 기능을 조용히 껐다 — outbox 를
     * 지나는 모든 이벤트가 traceparent 없이 나갔다. 그래서 구현이 여기 있고, 클래스 조건으로만 가른다: 트레이싱 클래스가
     * 있으면 트레이서를 쓰고, 없을 때만 {@code NONE} 이다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingTraceparents {

        /**
         * 트레이서 빈이 없으면(트레이싱을 끈 배포) 비어 있다 — 그때는 이어 붙일 트레이스가 없다. 빈의 등록 순서에 기대지
         * 않으려고 {@code @ConditionalOnBean} 대신 만들 때 찾는다.
         *
         * @param tracer     트레이서
         * @param propagator 전파기
         * @return 제공자
         */
        @Bean
        @ConditionalOnMissingBean
        TraceparentSupplier dawnlineTraceparentSupplier(ObjectProvider<Tracer> tracer,
                ObjectProvider<Propagator> propagator) {
            Tracer t = tracer.getIfAvailable();
            Propagator p = propagator.getIfAvailable();
            if (t == null || p == null) {
                LOG.info("트레이서가 없어 outbox 행에 traceparent 를 싣지 않습니다(트레이싱을 끈 배포).");
                return TraceparentSupplier.NONE;
            }
            return new TracerTraceparentSupplier(t, p);
        }
    }

    /** 트레이싱 클래스가 없는 소비자(도구) — 이어 붙일 트레이스가 없다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.tracing.Tracer")
    static class NoTraceparents {

        /** @return 언제나 비어 있는 제공자 */
        @Bean
        @ConditionalOnMissingBean
        TraceparentSupplier dawnlineTraceparentSupplier() {
            return TraceparentSupplier.NONE;
        }
    }

    /**
     * 보존 정리의 성공 나이 게이지 (§9.1 {@code dawnline_retention_last_success_age_seconds}, ADR-058 결정 6).
     *
     * <p>여기 두는 이유: 정리는 이 라이브러리({@code processed_events})와 서비스들(각자의 표)에 흩어져 있지만
     * 게이지는 하나여야 한다 — 이름과 라벨이 한 곳에서 나와야 알림 식 하나가 모든 표를 본다. 표는 정리기가
     * 생성자에서 등록한다(기동 때).
     *
     * @param meters 미터 레지스트리 (없으면 버리는 레지스트리)
     * @param clock  시각 출처 (없으면 저장 정밀도의 시스템 UTC)
     */
    @Bean
    @ConditionalOnMissingBean
    public RetentionAges dawnlineRetentionAges(ObjectProvider<MeterRegistry> meters, ObjectProvider<Clock> clock) {
        return new RetentionAges(meters.getIfAvailable(SimpleMeterRegistry::new),
                clock.getIfAvailable(MessagingAutoConfiguration::storagePrecisionClock));
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
        return new Ids(clock.getIfAvailable(MessagingAutoConfiguration::storagePrecisionClock), RandomGenerator.getDefault());
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
