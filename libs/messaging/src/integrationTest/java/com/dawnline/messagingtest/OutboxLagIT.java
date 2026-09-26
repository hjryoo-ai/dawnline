package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.messaging.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미발행 나이는 주입된 시계로 잰다 — 오프셋 시계 아래에서도 (DESIGN.md §5.6 「시뮬레이션 시계」, ADR-066 결정 4).
 *
 * <p>행의 {@code created_at} 은 주입된 시계가 적는다. 나이를 SQL 의 {@code now()} 로 재면 DB 의 벽시계와 비교하게 되고,
 * 시뮬레이션 오프셋만큼 <strong>음수</strong>가 된다 — {@code dawnline_outbox_lag_seconds} 가 음수면 {@code DawnlineOutboxLag} 는
 * 영원히 조용하고, 카오스 검증 표는 「지연 없음」으로 읽는다. 옛 SQL 에 이 테스트를 먼저 돌려 −28,800 초를 봤다(ADR-066 근거).
 *
 * <p>릴레이를 끈다 — 행이 발행되면 나이가 0 이 되어 아무것도 재지 못한다. 전용 DB 를 쓰는 이유는 {@link OutboxQuarantineIT} 와 같다.
 */
@SpringBootTest(
        classes = MessagingTestApplication.class,
        properties = {
            "dawnline.messaging.outbox.enabled=false",
            "dawnline.clock.offset=PT8H",
        })
@ActiveProfiles("sim")
class OutboxLagIT extends MessagingIntegrationTestBase {

    /**
     * 테스트 페이로드.
     *
     * @param orderId 주문 id
     */
    record OrderPlaced(String orderId) {
    }

    private static final String DATABASE = "dawnline_outbox_lag";

    /** 오프셋 — 시계가 이만큼 앞에 있다. */
    private static final Duration OFFSET = Duration.ofHours(8);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        useIsolatedDatabase(registry, DATABASE);
    }

    @Autowired
    private OutboxAppender appender;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 오프셋_시계_아래에서도_미발행_나이는_음수가_아니고_오프셋만큼_어긋나지_않는다() {
        // 전제: 시계가 정말 옮겨져 있다. 아니면 이 테스트는 벽시계끼리 비교하며 통과한다.
        assertThat(Duration.between(Clock.systemUTC().instant(), clock.instant()))
                .as("전제 — 주입된 시계가 벽시계보다 오프셋만큼 앞에 있다")
                .isBetween(OFFSET.minusMinutes(1), OFFSET.plusMinutes(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> appender.append(
                OutboxMessage.keyedByAggregate("Order", UUID.randomUUID(), "order.placed", 1,
                        new OrderPlaced(Topics.forEvent("order.placed", 1)))));

        double lag = new TransactionTemplate(transactionManager).execute(status -> outbox.unpublishedLagSeconds(clock.instant()));

        assertThat(lag).as("방금 쓴 행의 나이 — 오프셋(%s)만큼 음수가 아니다", OFFSET).isBetween(0.0, 60.0);
    }
}
