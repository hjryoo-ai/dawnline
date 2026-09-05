package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 계획에 쓸 시간 예산 (DESIGN.md §6.7).
 *
 * <p>기본 30초. 개선 단계는 잔여 예산을 클러스터 수로 나눠 배분한다.
 *
 * <p>기본값을 여기 상수로 두지 않는다 — 설정({@code dawnline.dispatch.*})에서 오고, 도메인이
 * 기본값을 알면 설정을 안 읽어도 돌아가서 설정이 죽은 코드가 된다.
 *
 * @param total    계획 전체의 상한
 * @param perRoute 라우트 하나에 쓸 수 있는 상한
 */
public record PlanningBudget(Duration total, Duration perRoute) {

    public PlanningBudget {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(perRoute, "perRoute");
        if (total.isNegative() || total.isZero()) {
            throw ValidationException.field("total", total, "전체 예산은 양수여야 합니다");
        }
        if (perRoute.isNegative() || perRoute.isZero()) {
            throw ValidationException.field("perRoute", perRoute, "라우트별 예산은 양수여야 합니다");
        }
        if (perRoute.compareTo(total) > 0) {
            throw ValidationException.field("perRoute", perRoute, "라우트별 예산이 전체 예산보다 클 수 없습니다");
        }
    }

    /**
     * 이 시각에 시작했을 때의 마감 시각.
     *
     * @param startedAt 시작 시각. 주입된 {@code Clock} 에서 온다 (불변규칙 12)
     */
    public Instant deadlineFrom(Instant startedAt) {
        return Objects.requireNonNull(startedAt, "startedAt").plus(total);
    }

    /**
     * 예산이 남았는가.
     *
     * @param startedAt 시작 시각
     * @param now       현재 시각
     */
    public boolean hasRemaining(Instant startedAt, Instant now) {
        return Objects.requireNonNull(now, "now").isBefore(deadlineFrom(startedAt));
    }
}
