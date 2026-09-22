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
 * 만들고, <strong>항상 같은 순서로 같은 개수</strong>를 뽑는다 — 쓰지 않을 값도 뽑는다.
 *
 * <h2>안 쓰는 값을 왜 뽑는가 — 공통 난수(common random numbers)</h2>
 * 시뮬레이션에서 두 설정을 비교할 때 쓰는 표준 기법이다. 각 자리의 난수를 <em>그 자리에
 * 고정</em>해 두면 설정을 바꿨을 때 달라지는 것이 <strong>바꾼 그것뿐</strong>이 되고, 차이를
 * 주입한 값 덕으로 돌릴 수 있다.
 *
 * <p>조건부로 뽑으면 그 성질이 깨진다. 지연 확률에 걸린 stop 만 크기를 한 번 더 뽑게 되므로
 * 다음 값의 자리가 밀리고, 「지연 확률을 0.3에서 0.4로 바꿨더니 <em>실패하는</em> stop 이 전부
 * 달라졌다」가 된다 — 파라미터 하나가 다른 축의 운명까지 바꾸면 시나리오 둘은 비교 대상이
 * 아니다. 그래서 {@link Draws} 는 넷을 <strong>언제나</strong> 뽑는다. 쓰이지 않는 추출을 지우면
 * 이 성질이 사라지고, 사라진 것은 시나리오를 비교하기 전까지 보이지 않는다.
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

    /**
     * 한 자리에서 뽑는 값 전부. <strong>순서와 개수가 고정</strong>이어야 한다 — 공통 난수의
     * 요점이고, 줄이면 파라미터 하나가 다른 축의 값을 바꾼다 (클래스 Javadoc).
     */
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
