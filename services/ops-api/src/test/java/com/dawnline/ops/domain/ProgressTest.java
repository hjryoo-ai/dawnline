package com.dawnline.ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 판정이 순서와 무관한가 — 모든 축의 모든 쌍에 대해 (ADR-051 결정 3).
 *
 * <p>축은 {@code Enum} 의 선언 순서라 새 값이 붙으면 이 검사도 저절로 그것을 돈다 — 값을
 * 열거하지 않는다(§13 규칙 2).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProgressTest {

    static Stream<Class<? extends Enum<?>>> axes() {
        return Stream.of(OrderStatus.class, DeliveryOutcome.class, WaveStatus.class, RouteStatus.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("axes")
    <S extends Enum<S>> void 두_사실이_어느_순서로_와도_칸에_남는_값이_같다(Class<S> axis) {
        for (S first : EnumSet.allOf(axis)) {
            for (S second : EnumSet.allOf(axis)) {
                assertThat(apply(apply(null, first), second))
                        .as("%s 다음 %s 와 그 반대", first, second)
                        .isEqualTo(apply(apply(null, second), first));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("axes")
    <S extends Enum<S>> void 빈_칸은_무엇이든_받고_같은_값은_세지_않고_뒤로는_센다(Class<S> axis) {
        for (S current : EnumSet.allOf(axis)) {
            assertThat(Progress.judge(null, current)).isEqualTo(Verdict.ADVANCE);
            for (S incoming : EnumSet.allOf(axis)) {
                Verdict expected = incoming.ordinal() > current.ordinal() ? Verdict.ADVANCE
                        : incoming == current ? Verdict.HOLD : Verdict.STALE;
                assertThat(Progress.judge(current, incoming)).as("%s ← %s", current, incoming).isEqualTo(expected);
            }
        }
    }

    @Test
    void 개정과_시각은_큰_쪽이_남는다() {
        assertThat(Progress.judgeVersion(null, 1)).isEqualTo(Verdict.ADVANCE);
        assertThat(Progress.judgeVersion(1, 2)).isEqualTo(Verdict.ADVANCE);
        // 같은 번호의 재발행은 새 정보를 담지 않는다 — 세지 않는다.
        assertThat(Progress.judgeVersion(2, 2)).isEqualTo(Verdict.HOLD);
        assertThat(Progress.judgeVersion(2, 1)).isEqualTo(Verdict.STALE);
        Instant earlier = Instant.EPOCH;
        assertThat(Progress.judgeVersion(earlier.plusSeconds(1), earlier)).isEqualTo(Verdict.STALE);
    }

    @Test
    void 쓰는_판정은_ADVANCE_하나다() {
        assertThat(EnumSet.allOf(Verdict.class).stream().filter(Verdict::writes)).containsExactly(Verdict.ADVANCE);
    }

    /** 판정대로 칸을 옮긴다 — 쓰는 판정이면 들어온 값, 아니면 그대로. */
    private static <S extends Enum<S>> S apply(S current, S incoming) {
        return Progress.judge(current, incoming).writes() ? incoming : current;
    }
}
