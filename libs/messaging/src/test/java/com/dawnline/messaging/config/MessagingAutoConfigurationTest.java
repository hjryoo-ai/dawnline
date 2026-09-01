package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.DlqRecordRecoverer;
import com.dawnline.messaging.kafka.NonRetryableEventException;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.TraceparentSupplier;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 자동설정 배선 — 무엇이 켜지고 무엇이 꺼지는가.
 *
 * <p>JPA 가 필요한 부분은 {@code OutboxRelayIT} 가 실제 DB 로 확인한다. 여기서는 인프라 없이 판단할 수 있는
 * 조건부 등록과 재정의 가능성만 본다.
 */
class MessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingAutoConfiguration.class,
                    MessagingJpaAutoConfiguration.class, MessagingKafkaAutoConfiguration.class));

    @Test
    void 인프라가_없어도_플랫폼_빈은_등록된다() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EventJson.class);
            assertThat(context).hasSingleBean(TraceparentSupplier.class);
            assertThat(context).hasSingleBean(Ids.class);
            assertThat(context).hasSingleBean(DawnlineMessagingProperties.class);
        });
    }

    @Test
    void JPA가_없으면_outbox_빈을_만들지_않는다() {
        // ops-api 처럼 outbox 가 필요 없는 모듈에서도 라이브러리가 기동을 막지 않아야 한다.
        runner.run(context -> assertThat(context).doesNotHaveBean(OutboxAppender.class));
    }

    @Test
    void 기본_TraceparentSupplier는_비어있다() {
        runner.run(context ->
                assertThat(context.getBean(TraceparentSupplier.class).currentTraceparent()).isEmpty());
    }

    @Test
    void 서비스가_EventJson을_재정의할_수_있다() {
        EventJson custom = EventJson.standard();

        runner.withBean(EventJson.class, () -> custom)
                .run(context -> assertThat(context.getBean(EventJson.class)).isSameAs(custom));
    }

    @Test
    void KafkaOperations가_있으면_DLQ_에러핸들러를_등록한다() {
        runner.withUserConfiguration(KafkaTemplateConfiguration.class)
                .withPropertyValues("spring.application.name=order-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(DlqRecordRecoverer.class);
                    assertThat(context).hasSingleBean(CommonErrorHandler.class);
                    DefaultErrorHandler handler = (DefaultErrorHandler) context.getBean(CommonErrorHandler.class);
                    assertThat(handler.removeClassification(NonRetryableEventException.class)).isFalse();
                });
    }

    @Test
    void retry를_끄면_에러핸들러를_등록하지_않는다() {
        runner.withUserConfiguration(KafkaTemplateConfiguration.class)
                .withPropertyValues("spring.application.name=order-service",
                        "dawnline.messaging.retry.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DlqRecordRecoverer.class);
                    assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
                });
    }

    @Test
    void 발행자_이름이_없으면_기동_시점에_실패한다() {
        // 봉투의 producer 는 계약 필수 필드다. 런타임에 이상한 값이 나가느니 기동에서 막는다.
        runner.withUserConfiguration(KafkaTemplateConfiguration.class)
                .withPropertyValues("spring.application.name=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 백프레셔_기본값을_컨슈머_팩토리에_적용한다() {
        runner.run(context -> {
            DefaultKafkaConsumerFactory<String, String> factory = consumerFactory();
            context.getBean(DefaultKafkaConsumerFactoryCustomizer.class).customize(factory);

            // §8.3 max.poll.records=100
            assertThat(factory.getConfigurationProperties())
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        });
    }

    @Test
    void 사용자가_max_poll_records를_지정하면_덮어쓰지_않는다() {
        runner.withPropertyValues("spring.kafka.consumer.max-poll-records=7").run(context -> {
            DefaultKafkaConsumerFactory<String, String> factory = consumerFactory();
            context.getBean(DefaultKafkaConsumerFactoryCustomizer.class).customize(factory);

            assertThat(factory.getConfigurationProperties())
                    .doesNotContainKey(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
        });
    }

    private static DefaultKafkaConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        return new DefaultKafkaConsumerFactory<>(configs, new StringDeserializer(), new StringDeserializer());
    }

    /** Boot 의 KafkaAutoConfiguration 대신 최소한의 {@link KafkaOperations} 빈만 놓는다. */
    @Configuration(proxyBeanMethods = false)
    static class KafkaTemplateConfiguration {

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            Map<String, Object> configs = new HashMap<>();
            configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            // 실제 프로듀서는 첫 send 에서 만들어진다. 이 테스트는 브로커에 연결하지 않는다.
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(configs, new StringSerializer(),
                    new StringSerializer()));
        }
    }
}
