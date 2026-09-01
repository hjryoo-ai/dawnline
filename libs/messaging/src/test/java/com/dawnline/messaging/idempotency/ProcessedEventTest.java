package com.dawnline.messaging.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link ProcessedEvent} 와 복합키 {@link ProcessedEventId} (DESIGN.md §5.1). */
class ProcessedEventTest {

    private static final UUID EVENT_ID = UUID.fromString("01a04dad-80da-79a6-95d0-ba4369830bdf");
    private static final Instant PROCESSED_AT = Instant.parse("2026-08-29T13:20:11.482Z");

    @Test
    void 생성자_값을_보존한다() {
        ProcessedEvent event = new ProcessedEvent(EVENT_ID, "dispatch-service", PROCESSED_AT);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.consumer()).isEqualTo("dispatch-service");
        assertThat(event.processedAt()).isEqualTo(PROCESSED_AT);
        assertThat(event.toString()).contains("dispatch-service");
    }

    @Test
    void 복합키_eventId와_consumer가_모두_같아야_같다() {
        ProcessedEventId key = new ProcessedEventId(EVENT_ID, "dispatch-service");

        assertThat(key).isEqualTo(new ProcessedEventId(EVENT_ID, "dispatch-service"));
        assertThat(key).hasSameHashCodeAs(new ProcessedEventId(EVENT_ID, "dispatch-service"));
        assertThat(key).isNotEqualTo(new ProcessedEventId(EVENT_ID, "tracking-service"));
        assertThat(key).isNotEqualTo(new ProcessedEventId(UUID.randomUUID(), "dispatch-service"));
        assertThat(key).isNotEqualTo("문자열");
        assertThat(key).isEqualTo(key);
    }

    @Test
    void 복합키_JPA용_무인자_생성자가_있다() {
        // @IdClass 는 public 무인자 생성자를 요구한다. record 로 만들 수 없는 이유다.
        ProcessedEventId key = new ProcessedEventId();

        assertThat(key.getEventId()).isNull();
        assertThat(key.getConsumer()).isNull();
        assertThat(key.toString()).contains("ProcessedEventId");
    }
}
