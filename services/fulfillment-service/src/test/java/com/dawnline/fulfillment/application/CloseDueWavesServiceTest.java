package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.application.port.out.WaveLock;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 컷오프 스케줄러 (§5.2, ADR-020 결정 2, ADR-025).
 *
 * <p>락이 실제로 무엇을 막는지는 {@code FulfillmentPersistenceIT} 가 실물 DB 로 본다. 여기서
 * 보는 것은 그 위의 판단이다 — 언제 닫는가, 무엇을 세어 내보내는가, 락이 없을 때 어떻게 하는가.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CloseDueWavesService — cutoffAt + grace 에 닫는다")
class CloseDueWavesServiceTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-06T01:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(90);
    private static final UUID CAMP_ID = UUID.randomUUID();

    private final InMemoryFulfillmentRepositories repositories = new InMemoryFulfillmentRepositories();
    private final RecordingEvents events = new RecordingEvents();
    private final CountingLock lock = new CountingLock();

    private CloseDueWavesService service(Instant now) {
        return new CloseDueWavesService(repositories.waveRepository(), repositories.orderRepository(),
                events, lock, new NoOpTransactionManager(), Clock.fixed(now, ZoneOffset.UTC),
                GRACE, 200);
    }

    private Wave openWave(Instant cutoffAt) {
        Wave wave = Wave.open(Ids.newId(), CAMP_ID, ServiceTier.SAME_DAY, cutoffAt);
        repositories.waveRepository().insertIfAbsent(wave);
        return wave;
    }

    private void admit(Wave wave) {
        repositories.orderRepository().insertIfAbsent(FulfillmentOrder.planned(Ids.newId(), Ids.newId(),
                wave.id(), CAMP_ID, Ids.newId(), Ids.newId(), wave.cutoffAt(),
                new TimeWindow(CUTOFF, CUTOFF.plusSeconds(3600)), false, null, CUTOFF));
    }

    @Test
    void 컷오프만_지나서는_닫지_않는다() {
        // grace 는 outbox·컨슈머 지연을 흡수하는 창이다. 그 안에 도착한 주문은 약속받은 그
        // 웨이브에 그대로 들어간다 (ADR-020 결정 2).
        openWave(CUTOFF);

        assertThat(service(CUTOFF.plusSeconds(30)).closeDue()).isZero();
        assertThat(events.closed).isEmpty();
    }

    @Test
    void 컷오프_더하기_grace_가_지나면_닫는다() {
        Wave wave = openWave(CUTOFF);

        assertThat(service(CUTOFF.plus(GRACE)).closeDue()).isEqualTo(1);

        assertThat(repositories.waves()).singleElement()
                .satisfies(closed -> {
                    assertThat(closed.status()).isEqualTo(WaveStatus.CLOSED);
                    assertThat(closed.closedAt()).isEqualTo(CUTOFF.plus(GRACE));
                });
        assertThat(events.closed).containsExactly(wave.id());
    }

    @Test
    void 마감_시점에_주문을_세어_내보낸다() {
        // 편입마다 카운터를 올리지 않는다 (ADR-025). 세는 방식은 매번 사실에서 다시 만든다.
        Wave wave = openWave(CUTOFF);
        admit(wave);
        admit(wave);

        service(CUTOFF.plus(GRACE)).closeDue();

        assertThat(repositories.waves()).singleElement()
                .extracting(Wave::orderCount).isEqualTo(2);
    }

    @Test
    void 취소된_주문은_카운트에서_빠진다() {
        // 취소가 카운트를 건드리는 분기가 없어도 맞는다 — 집계가 status='PLANNED' 만 세기 때문이다.
        Wave wave = openWave(CUTOFF);
        admit(wave);
        admit(wave);
        FulfillmentOrder one = repositories.orderRepository().findPlannedInWave(wave.id()).getFirst();
        one.cancel(CUTOFF.plusSeconds(10));
        repositories.orderRepository().update(one);

        service(CUTOFF.plus(GRACE)).closeDue();

        assertThat(repositories.waves()).singleElement().extracting(Wave::orderCount).isEqualTo(1);
    }

    @Test
    void 주문이_없어도_닫는다() {
        // 주문이 없는 캠프의 웨이브도 마감되어야 계획 파이프라인이 정상 종료된다 (§4.3).
        openWave(CUTOFF);

        assertThat(service(CUTOFF.plus(GRACE)).closeDue()).isEqualTo(1);
        assertThat(repositories.waves()).singleElement().extracting(Wave::orderCount).isEqualTo(0);
    }

    @Test
    void 락을_못_얻으면_건너뛴다() {
        // 다른 인스턴스가 처리 중이라는 뜻이다. 낭비를 줄이는 것이 이 락의 목적이다.
        openWave(CUTOFF);
        lock.grant = false;

        assertThat(service(CUTOFF.plus(GRACE)).closeDue()).isZero();
        assertThat(events.closed).isEmpty();
    }

    @Test
    void 락을_얻으면_반드시_놓는다() {
        openWave(CUTOFF);

        service(CUTOFF.plus(GRACE)).closeDue();

        assertThat(lock.acquired).isEqualTo(1);
        assertThat(lock.released).as("놓지 않으면 TTL 까지 그 웨이브가 잠긴다").isEqualTo(1);
    }

    @Test
    void 이미_닫힌_웨이브는_대상이_아니다() {
        Wave wave = openWave(CUTOFF);
        CloseDueWavesService service = service(CUTOFF.plus(GRACE));
        service.closeDue();

        assertThat(service.closeDue()).isZero();
        assertThat(events.closed).containsExactly(wave.id());
    }

    @Test
    void 발행이_실패하면_마감으로_치지_않는다() {
        // 예외가 밖으로 나가면 스케줄러 스레드가 그 작업을 더는 돌리지 않는다.
        //
        // 상태가 되돌아가는 것(롤백)은 여기서 확인하지 않는다 — 인메모리 저장소에는 트랜잭션이
        // 없고, 흉내 내면 흉내를 검사하는 테스트가 된다. 실제 롤백은 FulfillmentPersistenceIT
        // 계열이 실물 DB 로 본다.
        openWave(CUTOFF);
        events.fail = true;

        CloseDueWavesService service = service(CUTOFF.plus(GRACE));
        service.closeDueWaves();

        assertThat(events.closed).isEmpty();
        assertThat(lock.released).as("실패해도 락은 놓는다").isEqualTo(1);
    }

    /** 락 호출을 센다. */
    private static final class CountingLock implements WaveLock {

        private boolean grant = true;
        private int acquired;
        private int released;

        @Override
        public Optional<Guard> tryLock(UUID waveId) {
            if (!grant) {
                return Optional.empty();
            }
            acquired++;
            return Optional.of(() -> released++);
        }
    }

    /** 발행을 기록한다. */
    private static final class RecordingEvents implements FulfillmentEvents {

        private final List<UUID> closed = new ArrayList<>();
        private boolean fail;

        @Override
        public void planned(PlacedOrderSnapshot snapshot, UUID fcId, UUID campId, UUID zoneId,
                UUID waveId, Instant waveCutoffAt, TimeWindow window, boolean revised) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unserviceable(PlacedOrderSnapshot snapshot, UnserviceableReason reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void waveClosed(Wave wave) {
            if (fail) {
                throw new IllegalStateException("발행 실패");
            }
            closed.add(wave.id());
        }
    }

    /** 트랜잭션 경계 자체는 IT 가 본다. 여기서는 흐름만 본다. */
    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
