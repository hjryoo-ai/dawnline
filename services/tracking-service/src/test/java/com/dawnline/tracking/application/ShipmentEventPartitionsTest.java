package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.tracking.application.port.out.EventPartitions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 일 파티션 관리의 날짜 계산 (DESIGN.md §5.4).
 *
 * <p>여기서 검사하는 것은 SQL 이 아니라 <strong>어느 날짜를 넘기는가</strong>다. 실제 생성·삭제는
 * {@code ShipmentEventPartitionIT} 가 PostgreSQL 에서 본다.
 *
 * <p>시각 리터럴을 픽스처로 쓰지 않는다 — 기대값은 전부 주입한 시계에서 파생한다(CLAUDE.md).
 */
@DisplayName("shipment_events 일 파티션 — 날짜 계산")
class ShipmentEventPartitionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T03:21:44Z"), ZoneOffset.UTC);

    /** 시계가 말하는 오늘. 테스트의 모든 기대값이 여기서 나온다. */
    private static final LocalDate TODAY = LocalDate.ofInstant(CLOCK.instant(), ZoneOffset.UTC);

    private static final int AHEAD = 7;
    private static final int RETENTION = 30;

    private final RecordingPartitions partitions = new RecordingPartitions();

    private ShipmentEventPartitions partitionManager() {
        return new ShipmentEventPartitions(partitions, CLOCK, AHEAD, RETENTION);
    }

    @Test
    void 어제부터_오늘_더하기_선행일까지_만든다() {
        partitionManager().rotate();

        assertThat(partitions.ensureCalls)
                .as("자정 직후에 도착하는 어제 날짜의 스캔이 갈 곳이 있어야 한다")
                .containsExactly(new Ensure(TODAY.minusDays(1), 1 + 1 + AHEAD));
    }

    @Test
    void 마지막으로_만드는_날은_오늘_더하기_선행일이다() {
        partitionManager().rotate();

        Ensure ensure = partitions.ensureCalls.getFirst();
        LocalDate last = ensure.from().plusDays(ensure.days() - 1L);
        assertThat(last).isEqualTo(TODAY.plusDays(AHEAD));
    }

    @Test
    void 보존일보다_오래된_파티션만_지운다() {
        partitionManager().rotate();

        assertThat(partitions.dropCalls)
                .as("경계일 자신은 남는다 — 보존 30일은 오늘−30 을 포함한다")
                .containsExactly(TODAY.minusDays(RETENTION));
    }

    @Test
    void 게이지는_오늘을_포함해_앞으로_남은_날을_센다() {
        partitions.last = TODAY.plusDays(3);

        assertThat(partitionManager().partitionsAhead())
                .as("오늘·내일·모레·글피 = 4")
                .isEqualTo(4);
    }

    @Test
    void 마지막_파티션이_어제면_게이지는_0_이다() {
        partitions.last = TODAY.minusDays(1);

        assertThat(partitionManager().partitionsAhead())
                .as("오늘 도착하는 스캔이 이미 실패하고 있다는 뜻이다")
                .isZero();
    }

    @Test
    void 파티션이_하나도_없으면_게이지는_0_이다() {
        partitions.last = null;

        assertThat(partitionManager().partitionsAhead()).isZero();
    }

    @Test
    void 게이지는_첫_실행_전에도_카탈로그를_본다() {
        partitions.last = TODAY.plusDays(AHEAD);
        ShipmentEventPartitions manager = partitionManager();

        assertThat(manager.partitionsAhead())
                .as("기동 직후 첫 스크레이프가 0 을 보고하면 매 재기동마다 알림이 울린다")
                .isEqualTo(AHEAD + 1);
        assertThat(partitions.lastPartitionDayCalls)
                .as("한 번 읽은 뒤에는 캐시를 쓴다")
                .isEqualTo(1);

        manager.partitionsAhead();
        assertThat(partitions.lastPartitionDayCalls).isEqualTo(1);
    }

    @Test
    void 캐시된_값은_날이_지나면_저절로_줄어든다() {
        partitions.last = TODAY;
        ShipmentEventPartitions manager = partitionManager();
        assertThat(manager.partitionsAhead()).isEqualTo(1);

        // 같은 캐시를 하루 뒤의 시계로 읽으면 0 이다 — 멈춘 게이지는 건강해 보이기 때문에
        // 「마지막으로 만든 수」가 아니라 「남은 날」을 잰다.
        Clock tomorrow = Clock.offset(CLOCK, Duration.ofDays(1));
        ShipmentEventPartitions sameStateNextDay =
                new ShipmentEventPartitions(partitions, tomorrow, AHEAD, RETENTION);
        assertThat(sameStateNextDay.partitionsAhead()).isZero();
    }

    @Test
    void 결과에_만든_수와_지운_수와_덮인_마지막_날이_담긴다() {
        partitions.created = 2;
        partitions.dropped = 1;
        partitions.last = TODAY.plusDays(AHEAD);

        assertThat(partitionManager().rotate())
                .isEqualTo(new ShipmentEventPartitions.Rotated(2, 1, TODAY.plusDays(AHEAD)));
    }

    @Test
    void 스케줄_진입점은_예외를_삼킨다() {
        partitions.failure = new IllegalStateException("커넥션 없음");

        // 던지면 스케줄러 스레드가 다음 주기를 돌지 않는다. 실패는 게이지가 말한다(§9.1).
        partitionManager().maintain();

        assertThatThrownBy(() -> partitionManager().rotate())
                .as("직접 호출은 삼키지 않는다 — 테스트와 운영 수동 실행은 결과를 봐야 한다")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 보존이_선행_생성보다_짧으면_기동에서_막는다() {
        assertThatThrownBy(() -> new ShipmentEventPartitions(partitions, CLOCK, 7, 7))
                .as("방금 만든 파티션을 같은 실행이 지우는 설정이다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionDays");
    }

    @Test
    void 선행일과_보존일은_1_이상이어야_한다() {
        assertThatThrownBy(() -> new ShipmentEventPartitions(partitions, CLOCK, 0, RETENTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aheadDays");
        assertThatThrownBy(() -> new ShipmentEventPartitions(partitions, CLOCK, AHEAD, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionDays");
    }

    /** {@code ensure(from, days)} 호출 하나. */
    private record Ensure(LocalDate from, int days) {
    }

    /** 넘어온 날짜를 기록만 하는 포트. */
    private static final class RecordingPartitions implements EventPartitions {

        private final List<Ensure> ensureCalls = new ArrayList<>();
        private final List<LocalDate> dropCalls = new ArrayList<>();
        private int lastPartitionDayCalls;
        private int created;
        private int dropped;
        private @Nullable LocalDate last;
        private @Nullable RuntimeException failure;

        @Override
        public int ensure(LocalDate from, int days) {
            if (failure != null) {
                throw failure;
            }
            ensureCalls.add(new Ensure(from, days));
            return created;
        }

        @Override
        public int dropBefore(LocalDate before) {
            dropCalls.add(before);
            return dropped;
        }

        @Override
        public @Nullable LocalDate lastPartitionDay() {
            lastPartitionDayCalls++;
            return last;
        }
    }
}
