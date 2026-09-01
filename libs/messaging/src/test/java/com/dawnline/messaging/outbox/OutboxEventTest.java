package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link OutboxEvent} — 상태 전이는 메서드로만 (CLAUDE.md 불변규칙 6). */
class OutboxEventTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T13:20:11.482Z");
    private static final UUID ID = UUID.fromString("01a04dad-80da-79a6-95d0-ba4369830bdf");

    @Test
    void markPublished_발행시각을_기록한다() {
        OutboxEvent event = event();

        event.markPublished(CREATED_AT.plusSeconds(2));

        assertThat(event.isPublished()).isTrue();
        assertThat(event.publishedAt()).contains(CREATED_AT.plusSeconds(2));
    }

    @Test
    void markPublished_두_번_부르면_예외() {
        // 릴레이가 같은 행을 두 번 커밋하려 한 것이므로 조용히 덮어쓰지 않는다.
        OutboxEvent event = event();
        event.markPublished(CREATED_AT);

        assertThatIllegalStateException().isThrownBy(() -> event.markPublished(CREATED_AT.plusSeconds(1)))
                .withMessageContaining("이미 발행된");
    }

    @Test
    void 새_행은_미발행이다() {
        assertThat(event().isPublished()).isFalse();
        assertThat(event().publishedAt()).isEmpty();
    }

    @Test
    void toString_페이로드를_담지_않는다() {
        // 페이로드에는 주소 같은 개인정보가 들어간다 (§9.3 로깅 정책).
        String text = event().toString();

        assertThat(text).doesNotContain("강남대로").doesNotContain("payload");
        assertThat(text).contains(ID.toString()).contains("order.placed");
    }

    private static OutboxEvent event() {
        return new OutboxEvent(ID, "Order", ID, "order.placed", "dawnline.order.placed.v1", ID.toString(),
                "{\"eventType\":\"order.placed\"}", "{\"address\":\"서울특별시 강남구 강남대로 396\"}", CREATED_AT);
    }
}
