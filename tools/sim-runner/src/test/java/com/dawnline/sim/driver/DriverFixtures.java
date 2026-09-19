package com.dawnline.sim.driver;

import com.dawnline.sim.driver.AssignedRoute.PlannedStop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 기사 테스트의 공통 픽스처.
 *
 * <p>시각의 원점이 {@link Instant#EPOCH} 인 이유: {@link DriverSimulator} 는 시계를 읽지 않는다.
 * 계획 시각과 주입한 지연만으로 전부 계산하므로 원점이 무엇이든 결과가 같고, 따라서 이 픽스처는
 * 「값이 시계와 비교되는 경로」(CLAUDE.md 코딩 컨벤션)에 들어가지 않는다. 그 전제가 깨지면 —
 * 시뮬레이터가 {@code Instant.now()} 를 부르기 시작하면 — 불변규칙 12 위반이 먼저 드러난다.
 */
final class DriverFixtures {

    /** 계획 출발. 원점은 임의다 (클래스 javadoc). */
    static final Instant DEPARTURE = Instant.EPOCH.plus(Duration.ofHours(6));

    static final UUID ROUTE = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    static final UUID DRIVER = UUID.fromString("0199a000-0000-7000-8000-0000000000d1");

    private DriverFixtures() {
    }

    /** {@code n} 번째 주문 id. 읽는 쪽이 어느 stop 의 것인지 알아볼 수 있게 끝자리에 넣는다. */
    static UUID order(int n) {
        return UUID.fromString("0199a000-0000-7000-8000-%012d".formatted(n));
    }

    /**
     * stop 하나.
     *
     * @param seq            순번
     * @param arrivalMinutes 출발로부터 몇 분 뒤에 도착하는가
     * @param serviceMinutes 체류 몇 분
     * @param status         {@code null} 이면 {@code PLANNED}
     */
    static PlannedStop stop(int seq, int arrivalMinutes, int serviceMinutes, @Nullable String status) {
        return new PlannedStop(seq, List.of(order(seq)), 37.5 + seq * 0.01, 127.0 + seq * 0.01,
                DEPARTURE.plus(Duration.ofMinutes(arrivalMinutes)), serviceMinutes * 60, status);
    }

    /** 20분 이동 + 5분 체류가 세 번 이어지는 라우트. 구간은 20 · 15 · 15 분이다. */
    static AssignedRoute route(int revision) {
        return new AssignedRoute(ROUTE, revision, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(stop(1, 20, 5, null), stop(2, 40, 5, null), stop(3, 60, 5, null)));
    }

    /** 계획에서 몇 분 뒤인가. */
    static long minutesAfterDeparture(Instant instant) {
        return Duration.between(DEPARTURE, instant).toMinutes();
    }

    /** 고정 지연·고정 실패를 주는 {@link Jitter}. 정확한 값을 어설션하려면 seed 가 아니라 이것을 쓴다. */
    record FixedJitter(double factor, long departureSeconds, boolean fails) implements Jitter {

        @Override
        public double delayFactor(UUID routeId, int revision, int seq) {
            return factor;
        }

        @Override
        public boolean fails(UUID routeId, int revision, int seq) {
            return fails;
        }

        @Override
        public long departureDelaySeconds(UUID routeId, int revision) {
            return departureSeconds;
        }

        @Override
        public String failureReason(UUID routeId, int revision, int seq) {
            return "부재";
        }
    }
}
