package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 웨이브 애그리거트 (DESIGN.md §5.2, ADR-020 grace). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WaveTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-05T01:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(90);

    private static Wave open() {
        return Wave.open(UUID.randomUUID(), UUID.randomUUID(), ServiceTier.DAWN, CUTOFF);
    }

    private static Wave closed() {
        Wave wave = open();
        wave.beginClosing();
        wave.close(CUTOFF.plusSeconds(120));
        return wave;
    }

    @Test
    void 새_웨이브는_비어_있고_열려_있다() {
        Wave wave = open();

        assertThat(wave.status()).isEqualTo(WaveStatus.OPEN);
        assertThat(wave.orderCount()).isZero();
        assertThat(wave.closedAt()).isNull();
        assertThat(wave.cutoffAt()).isEqualTo(CUTOFF);
    }

    @Test
    void 주문을_넣고_뺀다() {
        Wave wave = open();

        wave.addOrder();
        wave.addOrder();
        wave.removeOrder();

        assertThat(wave.orderCount()).isEqualTo(1);
    }

    @Test
    void 비어_있는_웨이브에서는_뺄_수_없다() {
        assertThatThrownBy(open()::removeOrder).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 마감_중이면_주문을_넣을_수_없다() {
        Wave wave = open();
        wave.beginClosing();

        assertThatThrownBy(wave::addOrder).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(wave::removeOrder).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 마감_뒤에는_카운트를_건드릴_수_없다() {
        // ADR-022 — wave.closed 가 이미 그 orderCount 로 나갔다. 지금 줄이면 "그때 몇 건이
        // 있었나" 에 답이 둘이 된다. 그래서 애그리거트가 아예 막는다.
        Wave wave = open();
        wave.addOrder();
        wave.beginClosing();
        wave.close(CUTOFF.plusSeconds(120));

        assertThatThrownBy(wave::removeOrder).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(wave.orderCount()).isEqualTo(1);
    }

    @Test
    void 마감_수명주기를_따른다() {
        Wave wave = open();
        Instant closedAt = CUTOFF.plusSeconds(120);

        wave.beginClosing();
        assertThat(wave.status()).isEqualTo(WaveStatus.CLOSING);

        wave.close(closedAt);
        assertThat(wave.status()).isEqualTo(WaveStatus.CLOSED);
        assertThat(wave.closedAt()).isEqualTo(closedAt);

        wave.markPlanned();
        assertThat(wave.status()).isEqualTo(WaveStatus.PLANNED);
    }

    @Test
    void 단계를_건너뛸_수_없다() {
        // 웨이브 전이는 전부 자기 자신이거나 인과적으로 앞선 사건이라 순서가 뒤바뀔 수 없다.
        // 주문 상태와 달리 건너뜀을 수용할 이유가 없다.
        Wave wave = open();

        assertThatThrownBy(() -> wave.close(CUTOFF)).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(wave::markPlanned).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 계획_실패도_CLOSED_에서만_간다() {
        Wave wave = closed();

        wave.markPlanFailed();

        assertThat(wave.status()).isEqualTo(WaveStatus.PLAN_FAILED);
        // 종결이 아니다 — 운영자 재실행이 돌아올 자리다 (ADR-024 결정 3).
        assertThat(wave.status().isTerminal()).isFalse();
    }

    @Test
    void 계획_실패한_웨이브는_재실행_성공으로_되살아난다() {
        // §5.3 은 "운영자 재실행 가능" 이라고 적어 두었는데 웨이브 쪽에는 그 경로가 없었다.
        // 되살리는 것은 다시 나온 plan.completed 다 (ADR-024).
        Wave wave = closed();
        wave.markPlanFailed();

        wave.markPlanned();

        assertThat(wave.status()).isEqualTo(WaveStatus.PLANNED);
        assertThat(wave.status().isTerminal()).isTrue();
    }

    @Test
    void 계획된_웨이브를_다시_실패로_되돌리지_않는다() {
        // 늦게 온 1회차 plan.failed 다. 애그리거트는 예외로 막고, 리스너는 그 앞에서
        // hasProgressedPast 로 걸러 무시하고 커밋한다 (ADR-024 결정 4).
        Wave wave = closed();
        wave.markPlanned();

        assertThatThrownBy(wave::markPlanFailed).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(WaveStatus.PLANNED.hasProgressedPast(WaveStatus.PLAN_FAILED)).isTrue();
    }

    @Test
    void 마감_시각은_컷오프가_아니라_컷오프_더하기_grace_다() {
        // ADR-020 결정 2. grace 는 outbox·컨슈머 지연을 흡수하는 창이고, 그 안에 도착한 주문은
        // 약속받은 그 웨이브에 그대로 들어간다.
        Wave wave = open();

        assertThat(wave.isDueForClosing(CUTOFF, GRACE)).isFalse();
        assertThat(wave.isDueForClosing(CUTOFF.plus(GRACE), GRACE)).isTrue();
    }

    @Test
    void grace_경계_양쪽_1초() {
        Wave wave = open();

        assertThat(wave.isDueForClosing(CUTOFF.plus(GRACE).minusSeconds(1), GRACE)).isFalse();
        assertThat(wave.isDueForClosing(CUTOFF.plus(GRACE).plusSeconds(1), GRACE)).isTrue();
    }

    @Test
    void 이미_마감_중인_웨이브는_다시_마감_대상이_아니다() {
        // 스케줄러가 30초마다 도는 동안 같은 웨이브를 두 번 집지 않게 하는 첫 번째 방어다.
        // (두 번째는 Redis 락, 세 번째는 상태 전이 자체다.)
        Wave wave = open();
        wave.beginClosing();

        assertThat(wave.isDueForClosing(CUTOFF.plus(GRACE).plusSeconds(600), GRACE)).isFalse();
    }

    @Test
    void 저장된_상태에서_되살릴_수_있다() {
        Instant closedAt = CUTOFF.plusSeconds(200);
        UUID id = UUID.randomUUID();
        UUID campId = UUID.randomUUID();

        Wave wave = Wave.rehydrate(id, campId, ServiceTier.SAME_DAY, CUTOFF,
                WaveStatus.CLOSED, 42, closedAt, 7);

        assertThat(wave.id()).isEqualTo(id);
        assertThat(wave.campId()).isEqualTo(campId);
        assertThat(wave.serviceTier()).isEqualTo(ServiceTier.SAME_DAY);
        assertThat(wave.status()).isEqualTo(WaveStatus.CLOSED);
        assertThat(wave.orderCount()).isEqualTo(42);
        assertThat(wave.closedAt()).isEqualTo(closedAt);
        assertThat(wave.version()).isEqualTo(7);
    }
}
