package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 약속 배송창 계산 (DESIGN.md §2.2 표).
 *
 * <p>기댓값은 전부 UTC {@link Instant} 로 적는다. "익일 00:00" 같은 말을 그대로 옮겨 적으면
 * 시간대 변환 버그가 테스트에도 똑같이 들어가기 때문이다. KST 는 UTC+9 이므로
 * {@code 2026-09-04 00:00 KST = 2026-09-03T15:00:00Z} 다.
 */
@DisplayName("DeliveryPromise — 티어별 약속 배송창 (§2.2)")
class DeliveryPromiseTest {

    private final DeliveryPromise promises = DeliveryPromise.standard();

    /** 2026-09-03 09:00 KST. */
    private static final Instant MORNING = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void DAWN_은_익일_00시부터_07시까지다() {
        PromisedWindow window = promises.promiseFor(ServiceTier.DAWN, MORNING).window();

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-03T15:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-03T22:00:00Z"));
    }

    @Test
    void NEXT_DAY_는_익일_08시부터_22시까지다() {
        PromisedWindow window = promises.promiseFor(ServiceTier.NEXT_DAY, MORNING).window();

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-03T23:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-04T13:00:00Z"));
    }

    @Test
    void SAME_DAY_는_10시_이전이면_당일_10시부터_6시간이다() {
        PromisedWindow window = promises.promiseFor(ServiceTier.SAME_DAY, MORNING).window();

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-03T01:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-03T07:00:00Z"));
    }

    @Test
    void SAME_DAY_는_10시_정각이면_이미_1차_컷오프를_지난_것이다() {
        // 컷오프는 "그 시각까지" 다. 10:00:00 에 들어온 주문은 10:00 웨이브에 못 들어간다.
        Instant tenSharp = Instant.parse("2026-09-03T01:00:00Z");

        PromisedWindow window = promises.promiseFor(ServiceTier.SAME_DAY, tenSharp).window();

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-03T05:00:00Z"));  // 14:00 KST
    }

    @Test
    void SAME_DAY_는_14시_이후면_익일_10시로_넘어간다() {
        Instant afternoon = Instant.parse("2026-09-03T05:00:00Z");   // 14:00 KST

        PromisedWindow window = promises.promiseFor(ServiceTier.SAME_DAY, afternoon).window();

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-04T01:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-04T07:00:00Z"));
    }

    @Test
    void 자정_직전과_직후는_하루_차이가_난다() {
        // 여기가 시간대 버그가 실제로 사는 곳이다. UTC 로 계산하면 두 값이 같아진다.
        Instant justBeforeMidnight = Instant.parse("2026-09-03T14:59:00Z");  // 23:59 KST
        Instant justAfterMidnight = Instant.parse("2026-09-03T15:30:00Z");   // 익일 00:30 KST

        Instant before = promises.promiseFor(ServiceTier.DAWN, justBeforeMidnight).window().start();
        Instant after = promises.promiseFor(ServiceTier.DAWN, justAfterMidnight).window().start();

        assertThat(before).isEqualTo(Instant.parse("2026-09-03T15:00:00Z"));
        assertThat(after).isEqualTo(Instant.parse("2026-09-04T15:00:00Z"));
        assertThat(Duration.between(before, after)).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void 모든_티어의_창_길이가_티어_상한과_같다() {
        // PromisedWindow.of 가 상한을 검사하므로, 계산이 상한을 넘으면 여기서 예외가 난다.
        for (ServiceTier tier : ServiceTier.values()) {
            PromisedWindow window = promises.promiseFor(tier, MORNING).window();
            assertThat(window.window().duration())
                    .as("%s", tier)
                    .isEqualTo(tier.maxWindowLength());
        }
    }

    @Test
    void DAWN_과_NEXT_DAY_의_컷오프는_오늘이_끝나는_자정이다() {
        // §2.2: DAWN "전일 24:00", NEXT_DAY "24:00" — 둘 다 같은 순간을 가리킨다.
        Instant midnight = Instant.parse("2026-09-03T15:00:00Z");   // 2026-09-04 00:00 KST

        assertThat(promises.promiseFor(ServiceTier.DAWN, MORNING).cutoffAt()).isEqualTo(midnight);
        assertThat(promises.promiseFor(ServiceTier.NEXT_DAY, MORNING).cutoffAt()).isEqualTo(midnight);
    }

    @Test
    void DAWN_은_컷오프에서_바로_배송이_시작되고_NEXT_DAY_는_08시부터다() {
        // 컷오프와 창의 관계가 티어마다 다르다는 것이 cutoffAt 을 따로 싣는 이유다.
        DeliveryPromise.Promise dawn = promises.promiseFor(ServiceTier.DAWN, MORNING);
        DeliveryPromise.Promise nextDay = promises.promiseFor(ServiceTier.NEXT_DAY, MORNING);

        assertThat(dawn.window().start()).isEqualTo(dawn.cutoffAt());
        assertThat(nextDay.window().start()).isEqualTo(nextDay.cutoffAt().plus(Duration.ofHours(8)));
    }

    @Test
    void SAME_DAY_의_컷오프는_창의_시작과_같다() {
        for (Instant placedAt : new Instant[] {
                MORNING,                                     // 09:00 KST → 10:00 컷오프
                Instant.parse("2026-09-03T02:00:00Z"),       // 11:00 KST → 14:00 컷오프
                Instant.parse("2026-09-03T06:00:00Z")}) {    // 15:00 KST → 익일 10:00 컷오프
            DeliveryPromise.Promise promise = promises.promiseFor(ServiceTier.SAME_DAY, placedAt);
            assertThat(promise.cutoffAt()).as("%s", placedAt).isEqualTo(promise.window().start());
        }
    }

    @Test
    void 컷오프는_항상_접수_시각보다_뒤다() {
        // 이미 지난 컷오프를 실어 보내면 fulfillment 가 그 웨이브를 찾지 못하거나 즉시 마감된 웨이브에 넣는다.
        for (ServiceTier tier : ServiceTier.values()) {
            for (int hour = 0; hour < 24; hour++) {
                Instant placedAt = Instant.parse("2026-09-03T00:00:00Z").plus(Duration.ofHours(hour));
                assertThat(promises.promiseFor(tier, placedAt).cutoffAt())
                        .as("%s %s", tier, placedAt)
                        .isAfter(placedAt);
            }
        }
    }

    @Test
    void 같은_입력이면_항상_같은_창이다() {
        assertThat(promises.promiseFor(ServiceTier.DAWN, MORNING))
                .isEqualTo(promises.promiseFor(ServiceTier.DAWN, MORNING));
    }

    @Test
    void 다른_시간대로도_만들_수_있다() {
        DeliveryPromise utc = new DeliveryPromise(ZoneId.of("UTC"));

        // 같은 순간이라도 지역 날짜가 다르면 "익일" 이 달라진다.
        assertThat(utc.promiseFor(ServiceTier.DAWN, MORNING).window().start())
                .isEqualTo(Instant.parse("2026-09-04T00:00:00Z"))
                .isNotEqualTo(promises.promiseFor(ServiceTier.DAWN, MORNING).window().start());
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> promises.promiseFor(null, MORNING)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> promises.promiseFor(ServiceTier.DAWN, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeliveryPromise(null)).isInstanceOf(NullPointerException.class);
    }
}
