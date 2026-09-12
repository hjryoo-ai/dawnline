package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 제약 조합 축 ([ADR-033]) — 포함 관계가 한쪽으로만 간다.
 *
 * <p>조합을 <strong>손으로 나열하지 않는다.</strong> 검사는 전부 {@link ConstraintClass#all()} 을
 * 돌고, 그 목록이 두 축의 곱이라는 것을 먼저 확인한다 — 축이 늘면 조합이 따라 늘고, 늘어난
 * 조합이 조용히 검사 밖에 남지 않아야 한다(CLAUDE.md · §13).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ConstraintClass — 희소한 것은 능력이 아니라 조합이다")
class ConstraintClassTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");

    @Test
    void 조합은_두_축의_곱이고_화물이_만드는_값은_전부_그_안에_있다() {
        assertThat(ConstraintClass.all())
                .as("축 둘의 곱이다 — 축이 늘면 이 수가 늘어야 한다")
                .hasSize(4)
                .doesNotHaveDuplicates();

        List<ConstraintClass> fromParcels = List.of(
                ConstraintClass.of(Parcel.EMPTY),
                ConstraintClass.of(new Parcel(1, 1, true, false)),
                ConstraintClass.of(new Parcel(1, 1, false, true)),
                ConstraintClass.of(new Parcel(1, 1, true, true)));
        assertThat(fromParcels)
                .as("화물이 만들 수 있는 조합은 목록 밖에 없다")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ConstraintClass.all());
    }

    @Test
    void 포함은_한쪽으로만_간다() {
        for (ConstraintClass a : ConstraintClass.all()) {
            for (ConstraintClass b : ConstraintClass.all()) {
                if (a.equals(b)) {
                    continue;
                }
                assertThat(a.covers(b) && b.covers(a))
                        .as("%s 와 %s 가 서로를 포함하면 둘은 같은 조합이어야 한다",
                                a.label(), b.label())
                        .isFalse();
                if (a.covers(b)) {
                    assertThat(a.specificity())
                            .as("%s 가 %s 를 포함하면 더 특정하다", a.label(), b.label())
                            .isGreaterThan(b.specificity());
                }
            }
            assertThat(a.covers(ConstraintClass.NONE))
                    .as("아무것도 요구하지 않는 자리에는 누구나 앉는다")
                    .isTrue();
        }
    }

    @Test
    void 더_특정한_조합을_실을_수_있는_차는_더_적다() {
        List<VehicleSpec> fleet = List.of(
                vehicle(false, false), vehicle(true, false),
                vehicle(false, true), vehicle(true, true));

        for (ConstraintClass a : ConstraintClass.all()) {
            for (ConstraintClass b : ConstraintClass.all()) {
                if (!a.covers(b)) {
                    continue;
                }
                List<VehicleSpec> carriesA = fleet.stream().filter(a::carriedBy).toList();
                assertThat(fleet.stream().filter(b::carriedBy).toList())
                        .as("%s 를 싣는 차는 %s 를 싣는 차를 전부 포함한다", b.label(), a.label())
                        .containsAll(carriesA);
            }
        }
    }

    @Test
    void 일반_조합은_어떤_차량에도_실린다_그래서_예약할_것이_없다() {
        // [ADR-039] 가 일반 수요를 예약 대상에서 <em>뺀</em> 이유를 여기서 검사한다. 빼는 판단은
        // 「잊었다」와 구별되어야 한다(CLAUDE.md) — 일반 조합의 좌석은 아무도 막지 않으므로
        // 그 예약은 항등이다.
        for (VehicleSpec vehicle : List.of(vehicle(false, false), vehicle(true, false),
                vehicle(false, true), vehicle(true, true))) {
            assertThat(ConstraintClass.NONE.carriedBy(vehicle)).isTrue();
        }
        for (ConstraintClass demand : ConstraintClass.all()) {
            assertThat(demand.covers(ConstraintClass.NONE))
                    .as("%s 수요도 일반 좌석에 앉을 수 있다 — 그러니 막을 수 있는 수요가 없다",
                            demand.label())
                    .isTrue();
        }
    }

    private static VehicleSpec vehicle(boolean cold, boolean hazmat) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 10_000_000),
                new VehicleAttrs("VAN", cold, hazmat),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }
}
