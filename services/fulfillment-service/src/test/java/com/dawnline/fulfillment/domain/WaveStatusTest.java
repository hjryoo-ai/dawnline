package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 웨이브 전이표 전체 (DESIGN.md §5.2).
 *
 * <p>표를 <strong>한 곳에 모아 두는</strong> 이유가 여기 있다 — 25개 조합을 전부 훑을 수 있다.
 * 전이 조건이 각 메서드에 흩어져 있으면 이런 테스트를 쓸 수 없다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WaveStatusTest {

    /** §5.2 수명주기 그림을 그대로 옮긴 기대표. */
    private static final Map<WaveStatus, Set<WaveStatus>> EXPECTED = Map.of(
            WaveStatus.OPEN, Set.of(WaveStatus.CLOSING),
            WaveStatus.CLOSING, Set.of(WaveStatus.CLOSED),
            WaveStatus.CLOSED, Set.of(WaveStatus.PLANNED, WaveStatus.PLAN_FAILED),
            WaveStatus.PLANNED, Set.of(),
            // 운영자 재실행이 성공하면 돌아온다 (ADR-024 결정 3).
            WaveStatus.PLAN_FAILED, Set.of(WaveStatus.PLANNED));

    @Test
    void 전이표_25개_조합이_설계서와_같다() {
        List<String> mismatches = new ArrayList<>();
        for (WaveStatus from : WaveStatus.values()) {
            for (WaveStatus to : WaveStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                if (from.canTransitionTo(to) != expected) {
                    mismatches.add("%s → %s (기대 %s)".formatted(from, to, expected));
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void 같은_상태로의_전이는_허용하지_않는다() {
        // 중복은 Redis 락과 상태 조회가 앞에서 막는다. 여기서 조용히 통과시키면
        // 락이 새고 있다는 사실이 함께 숨는다.
        for (WaveStatus status : WaveStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s → %s", status, status).isFalse();
        }
    }

    @Test
    void 주문을_받는_상태는_OPEN_뿐이다() {
        for (WaveStatus status : WaveStatus.values()) {
            assertThat(status.acceptsOrders()).as("%s", status).isEqualTo(status == WaveStatus.OPEN);
        }
    }

    @Test
    void 마감이_발행된_뒤인지는_CLOSED_부터다() {
        // 이 값이 취소 시 order_count 를 건드릴지를 가른다 (ADR-022).
        assertThat(WaveStatus.OPEN.isClosedOrBeyond()).isFalse();
        assertThat(WaveStatus.CLOSING.isClosedOrBeyond()).isFalse();
        assertThat(WaveStatus.CLOSED.isClosedOrBeyond()).isTrue();
        assertThat(WaveStatus.PLANNED.isClosedOrBeyond()).isTrue();
        assertThat(WaveStatus.PLAN_FAILED.isClosedOrBeyond()).isTrue();
    }

    @Test
    void 계획이_끝난_상태는_둘이다() {
        // fulfillment_orders 정리 배치가 이 값으로 삭제 대상을 고른다 (ADR-023).
        assertThat(List.of(WaveStatus.values()).stream().filter(WaveStatus::isPlanningSettled).toList())
                .containsExactlyInAnyOrder(WaveStatus.PLANNED, WaveStatus.PLAN_FAILED);
    }

    @Test
    void 종료_상태는_PLANNED_뿐이다() {
        // PLAN_FAILED 는 종결이 아니다 — §5.3 이 적어 둔 "운영자 재실행 가능" 이 돌아올 자리가
        // 웨이브 쪽에 없었다 (ADR-024 결정 3).
        assertThat(WaveStatus.PLANNED.isTerminal()).isTrue();
        assertThat(WaveStatus.PLAN_FAILED.isTerminal()).isFalse();
        assertThat(WaveStatus.OPEN.isTerminal()).isFalse();
        assertThat(WaveStatus.CLOSING.isTerminal()).isFalse();
        assertThat(WaveStatus.CLOSED.isTerminal()).isFalse();
    }

    @Test
    void 계획_결과_두_전이는_축_위에서_한_방향이다() {
        // ADR-024 결정 4. plan.completed 와 plan.failed 는 다른 토픽이라 재실행 시 순서가
        // 뒤바뀔 수 있고, PLANNED 가 흡수 상태다.
        assertThat(WaveStatus.PLAN_FAILED.progress()).isLessThan(WaveStatus.PLANNED.progress());
        assertThat(List.of(WaveStatus.values()).stream().map(WaveStatus::progress).distinct().count())
                .as("다섯 상태가 축에서 서로 다른 자리를 갖는다").isEqualTo(5);
    }

    @Test
    void 이미_PLANNED_인_웨이브에_온_plan_failed_는_철_지난_이벤트다() {
        // 1회차 plan.failed 가 2회차 plan.completed 보다 늦게 도착하는 경우다. 그대로 두면
        // 라우트가 이미 나간 웨이브가 실패로 표시된다.
        assertThat(WaveStatus.PLANNED.hasProgressedPast(WaveStatus.PLAN_FAILED)).isTrue();
        assertThat(WaveStatus.PLANNED.hasProgressedPast(WaveStatus.PLANNED)).isTrue();

        // 반대 방향은 철 지난 것이 아니라 정상 전이다.
        assertThat(WaveStatus.PLAN_FAILED.hasProgressedPast(WaveStatus.PLANNED)).isFalse();
        assertThat(WaveStatus.CLOSED.hasProgressedPast(WaveStatus.PLAN_FAILED)).isFalse();
        assertThat(WaveStatus.CLOSED.hasProgressedPast(WaveStatus.PLANNED)).isFalse();
    }

    @Test
    void 축_규칙은_마감_전_세_상태를_봐주지_않는다() {
        // 앞의 세 전이는 자기 스케줄러가 옮긴다. 건너뜀은 순서 뒤바뀜이 아니라 버그이므로
        // canTransitionTo 가 그대로 막는다 (ADR-024 결정 4).
        assertThat(WaveStatus.OPEN.canTransitionTo(WaveStatus.CLOSED)).isFalse();
        assertThat(WaveStatus.OPEN.canTransitionTo(WaveStatus.PLANNED)).isFalse();
        assertThat(WaveStatus.CLOSING.canTransitionTo(WaveStatus.PLANNED)).isFalse();
    }
}
