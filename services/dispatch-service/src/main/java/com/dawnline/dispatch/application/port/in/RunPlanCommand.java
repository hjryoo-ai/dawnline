package com.dawnline.dispatch.application.port.in;

import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.common.GeoPoint;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 계획 실행 명령.
 *
 * @param waveId   대상 웨이브
 * @param campId   캠프
 * @param depot    캠프 좌표. {@code wave.closed} 의 스냅샷이고, 재실행 경로에서는 저장된 계획의
 *                 값을 쓴다 — 그 경로는 이벤트를 다시 받지 않는다 ({@code null} 허용)
 * @param strategy 전략 이름. {@code null} 이면 설정의 기본 전략 (§6.6)
 * @param mode     실행 모드. {@code null} 이면 {@code FULL} (§6.7 열화는 아직 자동이 아니다)
 * @param seed     난수 seed. {@code null} 이면 웨이브 id 에서 유도한다 — 같은 웨이브를 다시
 *                 돌리면 같은 결과가 나와야 하고(불변규칙 12), 시각에서 유도하면 그것이 깨진다
 */
public record RunPlanCommand(UUID waveId, UUID campId, @Nullable GeoPoint depot,
        @Nullable String strategy, @Nullable PlanMode mode, @Nullable Long seed) {

    public RunPlanCommand {
        Objects.requireNonNull(waveId, "waveId");
        Objects.requireNonNull(campId, "campId");
    }

    /** {@code wave.closed} 소비가 쓰는 형태 — 전략·모드·seed 는 기본값이다. */
    public static RunPlanCommand of(UUID waveId, UUID campId, GeoPoint depot) {
        return new RunPlanCommand(waveId, campId, Objects.requireNonNull(depot, "depot"),
                null, null, null);
    }

    /**
     * 재실행이 쓰는 형태 — 좌표는 저장된 계획에서 온다.
     *
     * @param waveId 대상 웨이브
     * @param campId 캠프
     */
    public static RunPlanCommand rerun(UUID waveId, UUID campId) {
        return new RunPlanCommand(waveId, campId, null, null, null, null);
    }

    /**
     * seed. 지정되지 않았으면 웨이브 id 에서 유도한다.
     *
     * <p>시각에서 유도하면 같은 웨이브를 다시 돌릴 때 다른 결과가 나오고, 그러면 "재실행했더니
     * 달라졌다" 가 버그인지 정상인지 구별할 수 없다.
     */
    public long effectiveSeed() {
        return seed != null ? seed
                : waveId.getMostSignificantBits() ^ waveId.getLeastSignificantBits();
    }

    /** 실행 모드. 기본은 {@code FULL}. */
    public PlanMode effectiveMode() {
        return mode != null ? mode : PlanMode.FULL;
    }
}
