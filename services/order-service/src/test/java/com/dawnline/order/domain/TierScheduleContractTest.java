package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.TierSchedule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * order-service 가 이벤트에 찍는 {@code cutoffAt} 과 {@code libs/common} 의 공유 표가 같은지
 * ([ADR-020](docs/adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 후속 정정 2).
 *
 * <h2>이 테스트의 역할은 회귀 가드다</h2>
 * {@link DeliveryPromise} 는 이제 §2.2 표를 <strong>갖지 않고</strong> {@link TierSchedule} 에
 * 위임한다(ADR-020 후속 정정 2). 그래서 두 값은 같은 계산에서 나오고, 이 테스트는 그것이
 * <em>다를 수 있는지</em>를 보는 것이 아니라 <strong>위임이 유지되는지</strong>를 본다.
 *
 * <p>처음에는 두 벌의 계산을 묶는 끈이었다. 그러나 "갈라지면 잡는다" 는 "갈라질 수 없다" 와
 * 다르고, 정직한 기록보다 참인 구조가 싸다 — 그래서 위임으로 바꾸고 이 테스트는 남겼다.
 * 누군가 이 클래스에 표를 다시 적으면 그 순간 두 계산이 생기고, 그때 여기가 깨진다.
 *
 * <p>테스트가 여전히 무언가를 증명하는 지점도 있다. {@link PromisedWindow#of} 의 티어별 길이
 * 상한은 order-service 에만 있으므로, 공유 표가 만든 창이 그 상한을 통과한다는 사실은
 * <em>합성</em>의 결과이고 여기서만 확인된다.
 *
 * <p>권위는 그대로 order-service 다 — 이벤트에 값을 찍는 것은 접수 경로 한 곳이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("TierScheduleContractTest — 두 계산이 같은 표를 본다")
class TierScheduleContractTest {

    private static final DeliveryPromise PROMISE = DeliveryPromise.standard();
    private static final TierSchedule SCHEDULE = TierSchedule.standard();

    /** 하루를 15분 간격으로 훑는다 — 컷오프 경계(10:00·14:00·24:00) 양쪽이 모두 들어간다. */
    private static final Instant DAY_START = Instant.parse("2026-09-05T15:00:00Z");

    @Test
    void 하루_전체에서_두_계산의_cutoffAt_이_같다() {
        List<String> mismatches = new ArrayList<>();

        for (ServiceTier tier : ServiceTier.values()) {
            for (int minutes = 0; minutes < 24 * 60; minutes += 15) {
                Instant placedAt = DAY_START.plus(Duration.ofMinutes(minutes));
                Instant fromOrderService = PROMISE.promiseFor(tier, placedAt).cutoffAt();
                Instant fromSharedTable = SCHEDULE.cutoffFor(tier.name(), placedAt);
                if (!fromOrderService.equals(fromSharedTable)) {
                    mismatches.add("%s @ %s: order=%s shared=%s"
                            .formatted(tier, placedAt, fromOrderService, fromSharedTable));
                }
            }
        }

        assertThat(mismatches)
                .as("두 계산이 갈라지면 약속한 창과 실제로 실릴 웨이브가 어긋난다")
                .isEmpty();
    }

    @Test
    void 경계_시각에서도_같다() {
        // 정확히 10:00·14:00 에 접수한 주문은 그 컷오프가 아니라 다음 컷오프에 실린다.
        // 경계를 어느 쪽으로 닫는지가 두 구현에서 갈리기 가장 쉬운 지점이다.
        for (String local : List.of("2026-09-06T01:00:00Z", "2026-09-06T05:00:00Z",
                "2026-09-05T15:00:00Z")) {
            Instant at = Instant.parse(local);
            for (ServiceTier tier : ServiceTier.values()) {
                assertThat(SCHEDULE.cutoffFor(tier.name(), at))
                        .as("%s @ %s", tier, at)
                        .isEqualTo(PROMISE.promiseFor(tier, at).cutoffAt());
            }
        }
    }

    @Test
    void 다음_컷오프는_항상_현재_컷오프보다_뒤다() {
        // 약속 개정 경로가 쓰는 값이다. 같거나 앞이면 무한 루프이거나 이미 마감된 웨이브다.
        for (ServiceTier tier : ServiceTier.values()) {
            Instant cutoff = PROMISE.promiseFor(tier, DAY_START).cutoffAt();
            Instant next = SCHEDULE.nextCutoffAfter(tier.name(), cutoff);

            assertThat(next).as("%s", tier).isAfter(cutoff);
            assertThat(SCHEDULE.nextCutoffAfter(tier.name(), next)).isAfter(next);
        }
    }

    @Test
    void 계약에_없는_티어는_거절한다() {
        assertThat(SCHEDULE.knows("DAWN")).isTrue();
        assertThat(SCHEDULE.knows("EXPRESS")).isFalse();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> SCHEDULE.cutoffFor("EXPRESS", DAY_START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 하루_전체에서_두_계산의_약속창도_같다() {
        // 개정 경로는 다음 컷오프만으로는 부족하다 — 그 컷오프의 배송창까지 있어야 고객에게 할
        // 약속이 정해진다(ADR-020 결정 3). 창도 같은 표에서 나오므로 여기서 함께 묶는다.
        List<String> mismatches = new ArrayList<>();

        for (ServiceTier tier : ServiceTier.values()) {
            for (int minutes = 0; minutes < 24 * 60; minutes += 15) {
                Instant placedAt = DAY_START.plus(Duration.ofMinutes(minutes));
                DeliveryPromise.Promise promise = PROMISE.promiseFor(tier, placedAt);
                com.dawnline.common.TimeWindow shared =
                        SCHEDULE.windowFor(tier.name(), promise.cutoffAt());
                if (!promise.window().window().equals(shared)) {
                    mismatches.add("%s @ %s: order=%s shared=%s"
                            .formatted(tier, placedAt, promise.window().window(), shared));
                }
            }
        }

        assertThat(mismatches)
                .as("창이 갈라지면 개정된 약속이 접수 시점의 규칙과 다른 값이 된다")
                .isEmpty();
    }

    @Test
    void 공유_표가_아는_티어와_enum_이_같다() {
        // 티어가 늘면 §2.2 표도 함께 늘어야 한다. 안 늘리면 그 티어의 주문이 접수에서 터진다.
        for (ServiceTier tier : ServiceTier.values()) {
            assertThat(SCHEDULE.knows(tier.name())).as("%s", tier).isTrue();
        }
    }
}
