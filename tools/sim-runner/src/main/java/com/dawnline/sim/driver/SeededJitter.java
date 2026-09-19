package com.dawnline.sim.driver;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.random.RandomGenerator;

/**
 * seed 하나에서 모든 지연·실패를 뽑는 {@link Jitter}.
 *
 * <p>좌표 {@code (seed, routeId, revision, seq)} 를 SplitMix64 로 섞어 그 자리만의 난수원을
 * 만들고, <strong>항상 같은 순서로 같은 개수</strong>를 뽑는다. 조건부로 뽑으면 다음 값의 자리가
 * 밀려서 「확률을 0.3에서 0.4로 바꿨더니 실패하는 stop 이 전부 달라졌다」가 된다 — 그러면
 * 시나리오 둘을 비교할 수 없다.
 *
 * <p>난수 알고리즘을 이름으로 고정하는 이유는 {@code SimRunnerConfig} 와 같다: JDK 가 올라가도
 * 같은 seed 가 같은 수열을 낸다.
 */
public final class SeededJitter implements Jitter {

    /**
     * 실패 사유. 자유 텍스트지만 도구가 지어내는 값이므로 목록으로 고정한다 — 개인정보가
     * 섞일 자리를 아예 만들지 않는다 (§9.3, {@code delivery.status.v1} 의 같은 필드).
     */
    private static final List<String> REASONS = List.of("부재", "주소 오류", "수취 거부", "접근 불가");

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private final long seed;
    private final LongFunction<RandomGenerator> generators;
    private final double delayProbability;
    private final double delayMagnitude;
    private final double failureProbability;
    private final long maxDepartureDelaySeconds;

    /**
     * @param seed                     시나리오 seed
     * @param generators               seed → 난수원. 주입하는 이유는 불변규칙 12 그대로다
     * @param delayProbability         구간에 지연이 걸릴 확률 (0.0 ~ 1.0)
     * @param delayMagnitude           지연의 최대 비율. 0.5 면 계획 이동 시간의 최대 1.5배
     * @param failureProbability       전달이 실패할 확률 (0.0 ~ 1.0)
     * @param maxDepartureDelaySeconds 캠프 출발 지연의 최대 초. 0 이면 정시 출발
     */
    public SeededJitter(long seed, LongFunction<RandomGenerator> generators, double delayProbability,
            double delayMagnitude, double failureProbability, long maxDepartureDelaySeconds) {
        this.seed = seed;
        this.generators = Objects.requireNonNull(generators, "generators");
        this.delayProbability = requireRatio(delayProbability, "delayProbability");
        this.failureProbability = requireRatio(failureProbability, "failureProbability");
        if (delayMagnitude < 0.0) {
            throw new IllegalArgumentException("delayMagnitude 는 0 이상이어야 합니다: " + delayMagnitude);
        }
        if (maxDepartureDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "maxDepartureDelaySeconds 는 0 이상이어야 합니다: " + maxDepartureDelaySeconds);
        }
        this.delayMagnitude = delayMagnitude;
        this.maxDepartureDelaySeconds = maxDepartureDelaySeconds;
    }

    private static double requireRatio(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException("%s 는 0.0 ~ 1.0 이어야 합니다: %s".formatted(name, value));
        }
        return value;
    }

    @Override
    public double delayFactor(UUID routeId, int revision, int seq) {
        Draws draws = draw(routeId, revision, seq);
        return draws.delayRoll() < delayProbability ? draws.delaySize() * delayMagnitude : 0.0;
    }

    @Override
    public boolean fails(UUID routeId, int revision, int seq) {
        return draw(routeId, revision, seq).failureRoll() < failureProbability;
    }

    @Override
    public long departureDelaySeconds(UUID routeId, int revision) {
        // seq 0 은 stop 이 아니다 — 계약의 seq 는 1부터라 이 자리는 캠프만의 것이다.
        return Math.round(draw(routeId, revision, 0).delaySize() * maxDepartureDelaySeconds);
    }

    @Override
    public String failureReason(UUID routeId, int revision, int seq) {
        int index = (int) (draw(routeId, revision, seq).reasonRoll() * REASONS.size());
        return REASONS.get(Math.clamp(index, 0, REASONS.size() - 1));
    }

    /** 한 자리에서 뽑는 값 전부. 순서와 개수가 고정이어야 한다 — 클래스 Javadoc 참고. */
    private record Draws(double delayRoll, double delaySize, double failureRoll, double reasonRoll) {
    }

    private Draws draw(UUID routeId, int revision, int seq) {
        RandomGenerator random = generators.apply(seedFor(routeId, revision, seq));
        return new Draws(random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextDouble());
    }

    private long seedFor(UUID routeId, int revision, int seq) {
        long mixed = mix(seed);
        mixed = mix(mixed ^ routeId.getMostSignificantBits());
        mixed = mix(mixed ^ routeId.getLeastSignificantBits());
        return mix(mixed ^ (((long) revision << Integer.SIZE) | Integer.toUnsignedLong(seq)));
    }

    /** SplitMix64 의 finalizer. 가까운 입력을 흩어 놓는 것이 여기서 필요한 전부다. */
    private static long mix(long value) {
        long z = value + GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
