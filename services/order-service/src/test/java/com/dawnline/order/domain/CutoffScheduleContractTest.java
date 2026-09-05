package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.CutoffSchedule;
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
 * <h2>왜 이 테스트가 그 정정의 조건인가</h2>
 * ADR-020 은 컷오프 계산을 order-service 한 곳에 두었다. 막으려던 것은 <strong>표의 복사본이
 * 둘이 되는 것</strong>이다. 그런데 약속 개정 경로에서 fulfillment 가 "다음 컷오프" 를 알아야
 * 하므로 표를 {@code libs/common} 으로 옮겨 <em>구현 하나를 둘이 쓰기로</em> 했다.
 *
 * <p>그 결정이 안전한 이유는 "같은 클래스를 참조하면 갈라질 수 없다" 인데, order-service 는
 * 아직 자기 {@link DeliveryPromise} 로 계산한다 — 약속창까지 함께 만들어야 하기 때문이다.
 * 즉 <strong>여기에는 여전히 두 벌의 계산이 있다.</strong> 이 테스트가 그 둘을 묶는다.
 * 어느 한쪽만 고치면 여기서 깨진다.
 *
 * <p>권위는 order-service 다 — 이벤트에 값을 찍는 것은 접수 경로 한 곳이다. 공유 표는 그 값을
 * <em>재현</em>할 수 있어야 하고, 그것이 fulfillment 가 다음 컷오프를 물을 자격이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CutoffScheduleContractTest — 두 계산이 같은 표를 본다")
class CutoffScheduleContractTest {

    private static final DeliveryPromise PROMISE = DeliveryPromise.standard();
    private static final CutoffSchedule SCHEDULE = CutoffSchedule.standard();

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
    void 공유_표가_아는_티어와_enum_이_같다() {
        // 티어가 늘면 §2.2 표도 함께 늘어야 한다. 안 늘리면 그 티어의 주문이 접수에서 터진다.
        for (ServiceTier tier : ServiceTier.values()) {
            assertThat(SCHEDULE.knows(tier.name())).as("%s", tier).isTrue();
        }
    }
}
