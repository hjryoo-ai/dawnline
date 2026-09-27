package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * 근무 종료 전에 캠프로 돌아올 수 있어야 한다 (§6.3 {@code SHIFT_WINDOW}, HARD).
 *
 * <p>판정 기준은 <strong>복귀</strong> 시각이다 — 마지막 stop 에 도착하는 시각이 아니라 캠프까지
 * 돌아오는 시각이다. 그 구간이 라우트의 일부라서 {@link RouteState} 가 캠프와 거리를 들고 있다.
 *
 * <p>{@code bufferMinutes} 는 계획과 현실의 차이를 흡수한다. 0 으로 두면 계획상 정확히 근무 종료에
 * 복귀하는 라우트가 만들어지고, 그것은 실제로는 매번 초과 근무다.
 *
 * <h2>근무창이 허락하는 stop 수도 답한다 (ADR-039 후속 2)</h2>
 * {@code MAX_STOPS_PER_ROUTE} 가 120 을 답하듯, 이 룰은 근무창에 들어가는 stop 수를 답한다 — 좌석 예약이 120 으로 센 「자유석」은 시간이
 * 허락하지 않는 자리였다(두 번째 {@code peak-day}, 미배정 607건 중 {@code shift-window} 595). 답은 {@link StopTime} 의 세 상수가 있을
 * 때만이다 — 그 셋은 한 시나리오의 지리와 소포 분포를 요약한 <strong>추정치</strong>라 데이터(룰 파라미터)에 두고, 출처와 다시 볼 조건은
 * DESIGN §6.3 표에 있다. 없으면 기본값(「모른다」)이다.
 *
 * @param stopTime 시간 상한의 상수 셋. 없으면 이 룰은 판정만 하고 상한을 말하지 않는다
 */
public record ShiftWindowRule(String name, int priority, int bufferMinutes, @Nullable StopTime stopTime)
        implements HardRule {

    /**
     * 근무창이 허락하는 stop 수를 셀 상수 셋 — 모두 초.
     *
     * <p>기본값(시드)은 두 번째 {@code peak-day} 새벽 계획의 실측이다 — 야간 냉장 트럭 46대 평균으로 stop 하나의 작업 158 · stop 사이
     * 이동 76, 개정 1 라우트 38대로 캠프 왕복 827(첫 구간 380 + 복귀 447). 23:58 계획 · 07:30 마감이면 ⌊(27,120 − 827) ÷ 234⌋ = 112.
     * 다른 지리 · 소포 분포에서는 웨이브의 후보에서 파생한다(DESIGN §6.3).
     *
     * @param serviceSeconds   stop 하나의 작업
     * @param legSeconds       stop 사이 평균 이동
     * @param depotLegsSeconds 캠프 왕복(첫 구간 + 복귀)
     */
    public record StopTime(int serviceSeconds, int legSeconds, int depotLegsSeconds) {

        public StopTime {
            if (serviceSeconds + legSeconds <= 0 || serviceSeconds < 0 || legSeconds < 0 || depotLegsSeconds < 0) {
                throw new IllegalArgumentException("stop 하나의 시간은 양수이고 셋 다 음수가 아니어야 합니다: service=%d, leg=%d, depot=%d"
                        .formatted(serviceSeconds, legSeconds, depotLegsSeconds));
            }
        }
    }

    public ShiftWindowRule {
        if (bufferMinutes < 0) {
            throw ValidationException.field(name + ".params.bufferMinutes", bufferMinutes,
                    "버퍼는 음수일 수 없습니다");
        }
    }

    /** 판정만 하는 룰 — 상한을 말하지 않는다. */
    public ShiftWindowRule(String name, int priority, int bufferMinutes) {
        this(name, priority, bufferMinutes, null);
    }

    private static final String[] STOP_TIME_KEYS = {"serviceSeconds", "legSeconds", "depotLegsSeconds"};

    static ShiftWindowRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        int present = 0;
        for (String key : STOP_TIME_KEYS) {
            present += params.has(key) ? 1 : 0;
        }
        if (present != 0 && present != STOP_TIME_KEYS.length) {
            // 셋 중 일부만 있으면 식이 설 수 없다 — 조용히 「모른다」로 두면 적어 둔 값이 아무 일도 하지 않는다.
            throw ValidationException.field(definition.name() + ".params", definition.params(),
                    "serviceSeconds · legSeconds · depotLegsSeconds 는 함께 있거나 함께 없어야 합니다");
        }
        StopTime stopTime = present == 0 ? null : new StopTime(
                nonNegative(params, definition.name(), "serviceSeconds"),
                nonNegative(params, definition.name(), "legSeconds"),
                nonNegative(params, definition.name(), "depotLegsSeconds"));
        return new ShiftWindowRule(definition.name(), definition.priority(),
                params.requireInt("bufferMinutes"), stopTime);
    }

    private static int nonNegative(RuleParams params, String name, String key) {
        int value = params.requireInt(key);
        if (value < 0) {
            throw ValidationException.field(name + ".params." + key, value, "음수일 수 없습니다");
        }
        return value;
    }

    @Override
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        Instant latestReturn = vehicle.shift().end().minus(Duration.ofMinutes(bufferMinutes));
        Instant actualReturn = state.returnTimeIfAppended(stop);
        if (!actualReturn.isAfter(latestReturn)) {
            return Feasibility.ok();
        }
        return Feasibility.violated(name, "복귀 %s 가 근무 종료 %s − 버퍼 %d분을 넘깁니다"
                .formatted(actualReturn, vehicle.shift().end(), bufferMinutes),
                Duration.between(latestReturn, actualReturn).toMinutes());
    }

    /**
     * 이 차량이 {@code startAt} 에 계획되면 근무창에 들어가는 stop 수 —
     * ⌊(근무 끝 − 버퍼 − 출발 − 캠프 왕복) ÷ (작업 + 이동)⌋, 음수면 0.
     *
     * <p>출발은 계획 시작과 근무 시작 중 늦은 쪽이다({@link RouteState#empty} 와 같은 규칙). 차량만으로는 답할 수 없다 — 주간조(09–22시)가
     * 14시에 계획되면 남은 근무는 7.5시간이지 13시간이 아니다.
     */
    @Override
    public OptionalInt routeStopCap(VehicleSpec vehicle, Instant startAt) {
        if (stopTime == null) {
            return OptionalInt.empty();
        }
        Instant departAt = startAt.isBefore(vehicle.shift().start()) ? vehicle.shift().start() : startAt;
        long usable = Duration.between(departAt, vehicle.shift().end().minus(Duration.ofMinutes(bufferMinutes)))
                .toSeconds() - stopTime.depotLegsSeconds();
        long perStop = (long) stopTime.serviceSeconds() + stopTime.legSeconds();
        return OptionalInt.of((int) Math.clamp(usable / perStop, 0L, Integer.MAX_VALUE));
    }
}
