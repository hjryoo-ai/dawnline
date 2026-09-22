package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.ROUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.LongFunction;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** seed 하나에서 모든 지연·실패가 나온다 (불변규칙 12). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SeededJitterTest {

    private static final LongFunction<RandomGenerator> GENERATORS =
            seed -> RandomGeneratorFactory.of("L64X128MixRandom").create(seed);

    private static SeededJitter jitter(long seed, double delayProbability, double failureProbability) {
        return new SeededJitter(seed, GENERATORS, delayProbability, 0.5, failureProbability, 1800L);
    }

    @Test
    void 같은_좌표는_몇_번을_물어도_같은_값이다() {
        // 개정이 오면 시뮬레이터가 다시 불린다. 그때 같은 구간의 지연이 달라지면 순수 함수가 아니다.
        SeededJitter jitter = jitter(1L, 0.5, 0.5);

        assertThat(jitter.delayFactor(ROUTE, 1, 2)).isEqualTo(jitter.delayFactor(ROUTE, 1, 2));
        assertThat(jitter.fails(ROUTE, 1, 2)).isEqualTo(jitter.fails(ROUTE, 1, 2));
    }

    @Test
    void 좌표가_다르면_값이_흩어진다() {
        SeededJitter jitter = jitter(1L, 1.0, 0.0);

        assertThat(IntStream.rangeClosed(1, 20).mapToDouble(seq -> jitter.delayFactor(ROUTE, 1, seq))
                .distinct().count()).isGreaterThan(15L);
        // 재계획은 구간을 바꾼다. 개정이 달라지면 같은 seq 도 다른 구간이다.
        assertThat(jitter.delayFactor(ROUTE, 1, 1)).isNotEqualTo(jitter.delayFactor(ROUTE, 2, 1));
    }

    @Test
    void 다른_seed_는_다른_시나리오다() {
        assertThat(jitter(1L, 1.0, 0.0).delayFactor(ROUTE, 1, 1))
                .isNotEqualTo(jitter(2L, 1.0, 0.0).delayFactor(ROUTE, 1, 1));
    }

    @Test
    void 확률_0_은_아무것도_주입하지_않는다() {
        SeededJitter jitter = jitter(7L, 0.0, 0.0);

        assertThat(IntStream.rangeClosed(1, 50)).allSatisfy(seq -> {
            assertThat(jitter.delayFactor(ROUTE, 1, seq)).isZero();
            assertThat(jitter.fails(ROUTE, 1, seq)).isFalse();
        });
    }

    @Test
    void 확률_1_은_전부_주입한다() {
        SeededJitter jitter = jitter(7L, 1.0, 1.0);

        assertThat(IntStream.rangeClosed(1, 50)).allSatisfy(seq -> {
            assertThat(jitter.delayFactor(ROUTE, 1, seq)).isPositive().isLessThanOrEqualTo(0.5);
            assertThat(jitter.fails(ROUTE, 1, seq)).isTrue();
        });
    }

    @Test
    void 확률을_바꿔도_다른_축의_값은_그대로다() {
        // 조건부로 뽑으면 다음 값의 자리가 밀려서 "지연 확률을 바꿨더니 실패하는 stop 이
        // 전부 달라졌다" 가 된다. 그러면 시나리오 둘을 비교할 수 없다.
        boolean[] withoutDelay = failures(jitter(3L, 0.0, 0.4));
        boolean[] withDelay = failures(jitter(3L, 1.0, 0.4));

        assertThat(withDelay).containsExactly(withoutDelay);
    }

    @Test
    void 출발_지연은_상한_안에서_나온다() {
        SeededJitter jitter = jitter(11L, 1.0, 0.0);

        assertThat(IntStream.rangeClosed(1, 50)).allSatisfy(revision -> assertThat(
                jitter.departureDelaySeconds(UUID.nameUUIDFromBytes(("r" + revision).getBytes()), 1))
                .isBetween(0L, 1800L));
    }

    @Test
    void 확률은_0_과_1_사이여야_한다() {
        assertThatThrownBy(() -> jitter(1L, 1.5, 0.0)).hasMessageContaining("delayProbability");
        assertThatThrownBy(() -> jitter(1L, 0.0, -0.1)).hasMessageContaining("failureProbability");
        assertThatThrownBy(() -> new SeededJitter(1L, GENERATORS, 0.0, -1.0, 0.0, 0L))
                .hasMessageContaining("delayMagnitude");
    }

    private static boolean[] failures(SeededJitter jitter) {
        boolean[] result = new boolean[50];
        for (int seq = 0; seq < result.length; seq++) {
            result[seq] = jitter.fails(ROUTE, 1, seq + 1);
        }
        return result;
    }
}
