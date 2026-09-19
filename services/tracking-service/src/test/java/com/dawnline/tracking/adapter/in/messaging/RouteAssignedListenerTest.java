package com.dawnline.tracking.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.Outcome;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@code route.assigned} 리스너 — 멱등 두 겹과 지난 개정의 계수 (DESIGN.md §4.1, §8.5).
 *
 * <p>입력은 <strong>계약 예시 파일</strong>이다(불변규칙 8). 손으로 만든 봉투는 발행자가 실제로
 * 내는 모양과 갈라질 수 있고, 그 갈라짐은 이 테스트가 통과하는 동안 일어난다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("route.assigned 리스너")
class RouteAssignedListenerTest {

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final EventJson JSON = CONTRACTS.json();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T21:10:00Z"), ZoneOffset.UTC);

    private InMemoryProcessedEvents processed;
    private RecordingUseCase useCase;
    private MeterRegistry meters;
    private RouteAssignedListener listener;

    @BeforeEach
    void setUp() {
        processed = new InMemoryProcessedEvents();
        useCase = new RecordingUseCase();
        meters = new SimpleMeterRegistry();
        IdempotentConsumer consumer = new IdempotentConsumer(processed, new NoOpTransactions(),
                meters, CLOCK);
        listener = new RouteAssignedListener(consumer, useCase, JSON, meters);
    }

    @Test
    void 봉투를_열어_유스케이스에_명령으로_넘긴다() {
        listener.onRouteAssigned(record(example("route.assigned.v1.revised.example.json")));

        assertThat(useCase.calls).singleElement().satisfies(assignment -> {
            assertThat(assignment.routeId())
                    .isEqualTo(UUID.fromString("01a04e09-854a-770c-b7b7-03325dccc708"));
            assertThat(assignment.revision()).isEqualTo(2);
            assertThat(assignment.stops()).hasSize(3);
        });
    }

    @Test
    void 지난_개정은_stale_로_센다() {
        // 거부가 아니다 — 순서 역전을 흡수한 것이고, 늘어나면 어딘가 지연이 커졌다는 뜻이다.
        useCase.outcome = Outcome.stale();

        listener.onRouteAssigned(record(example("route.assigned.v1.example.json")));

        assertThat(staleCount()).isEqualTo(1.0);
        assertThat(rejectedCount())
                .as("dawnline_event_rejected_total 은 「처리하지 못했다」를 세는 값이다")
                .isZero();
    }

    @Test
    void 반영했으면_stale_을_세지_않는다() {
        listener.onRouteAssigned(record(example("route.assigned.v1.example.json")));

        assertThat(staleCount()).isZero();
    }

    @Test
    void 같은_이벤트를_다시_받으면_유스케이스를_부르지_않는다() {
        // 첫째 겹 — processed_events (불변규칙 2).
        ConsumerRecord<String, String> record = record(example("route.assigned.v1.example.json"));

        listener.onRouteAssigned(record);
        listener.onRouteAssigned(record);

        assertThat(useCase.calls).hasSize(1);
        assertThat(staleCount())
                .as("중복은 지난 개정과 다른 일이다 — route_revisions 까지 가지도 않는다")
                .isZero();
    }

    @Test
    void 약속창이_없는_이벤트는_멈춘다() {
        // 재시도 뒤 DLQ 로 간다(§4.6). 조용히 넘기면 창 없는 배송이 at-risk 판정 밖으로 사라진다.
        String withoutWindow = example("route.assigned.v1.example.json")
                .replaceAll(",\\s*\"promisedWindow\"\\s*:\\s*\\{[^}]*}", "");

        // 전제를 먼저 말한다 — 지우지 못했다면 이 테스트는 통과하면서 아무것도 검사하지 않는다.
        assertThat(withoutWindow).doesNotContain("promisedWindow");
        assertThatThrownBy(() -> listener.onRouteAssigned(record(withoutWindow)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promisedWindow");
        assertThat(useCase.calls).isEmpty();
    }

    // --- 픽스처 --------------------------------------------------------------

    private double staleCount() {
        return counterOrZero(MessagingMetrics.EVENT_STALE);
    }

    private double rejectedCount() {
        return counterOrZero(MessagingMetrics.EVENT_REJECTED);
    }

    private double counterOrZero(String name) {
        try {
            return meters.get(name).tag(MessagingMetrics.TAG_CONSUMER, RouteAssignedListener.CONSUMER)
                    .counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(RouteAssignedListener.ROUTE_ASSIGNED_TOPIC, 0, 0L, "key", value);
    }

    private static String example(String fileName) {
        Path file = CONTRACTS.contractsDirectory().resolve("examples").resolve(fileName);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 예시를 읽지 못했습니다: " + file, e);
        }
    }

    private static final class RecordingUseCase implements ApplyRouteAssignmentUseCase {

        private final List<RouteAssignment> calls = new ArrayList<>();
        private Outcome outcome = new Outcome(Outcome.Kind.APPLIED, 1, 0, 0, 0);

        @Override
        public Outcome apply(RouteAssignment assignment) {
            calls.add(assignment);
            return outcome;
        }
    }

    private static final class InMemoryProcessedEvents implements ProcessedEventRepository {

        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean markProcessed(UUID eventId, String consumer, Instant processedAt) {
            return seen.add(eventId + "|" + consumer);
        }

        @Override
        public boolean isProcessed(UUID eventId, String consumer) {
            return seen.contains(eventId + "|" + consumer);
        }

        @Override
        public int deleteProcessedBefore(Instant processedAtBefore, int limit) {
            return 0;
        }
    }

    /** 커밋·롤백을 하지 않는 트랜잭션 관리자 — 이 테스트가 보는 것은 DB 가 아니라 분기다. */
    private static final class NoOpTransactions implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            // 없음
        }

        @Override
        public void rollback(TransactionStatus status) {
            // 없음
        }
    }
}
