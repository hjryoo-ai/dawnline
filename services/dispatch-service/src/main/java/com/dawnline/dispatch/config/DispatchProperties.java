package com.dawnline.dispatch.config;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.dispatch.*} 설정 (DESIGN.md §6.6, §6.7).
 *
 * @param plan     계획 실행 설정
 * @param distance 거리 계산 설정
 * @param priority 후보 우선도 점수표
 * @param degrade  열화 모드 임계 (§6.7)
 * @param replan   부분 재계획 설정 (§6.8)
 */
@ConfigurationProperties(prefix = "dawnline.dispatch")
public record DispatchProperties(@DefaultValue Plan plan, @DefaultValue Distance distance,
        @DefaultValue Priority priority, @DefaultValue Degrade degrade,
        @DefaultValue Replan replan) {

    /**
     * 부분 재계획 (§6.8, [ADR-048]).
     *
     * @param cooldown           라우트당 쿨다운. 설계서 §6.8 5단계가 <strong>10분</strong>으로
     *                           정했다. 지키는 것은 알림 수가 아니라 <strong>정확성</strong>이다 —
     *                           tracking 의 Redis 쿨다운과 집이 다른 이유다([ADR-046] 결정 3)
     * @param deviationTolerance 페이로드의 편차와 자기 편차가 이만큼까지는 갈려도 세지 않는다.
     *                           두 값은 서로 다른 시각 원천에서 오므로(스캔의 {@code occurredAt}
     *                           과 저장 정밀도로 자른 {@code Clock}) 초 단위 일치를 요구하면 그
     *                           카운터는 늘 켜져 있어 아무 말도 하지 않는다
     */
    public record Replan(@DefaultValue("10m") Duration cooldown,
            @DefaultValue("60s") Duration deviationTolerance) {
    }

    /**
     * 열화 사다리의 임계 (§6.7, [ADR-034] + 후속 정정). 앞의 두 수는 설계서 그대로다 —
     * <strong>랙 3 웨이브</strong>, <strong>예산의 80%</strong>.
     *
     * <p>설정으로 두는 이유는 이 셋이 정책이기 때문이다. 캠프 규모와 러너 사양에 따라 "밀렸다"
     * 의 뜻이 달라지고, 임계를 바꾸는 데 배포가 필요하면 성수기 한복판에서 바꿀 수 없다.
     *
     * @param maxBacklogWaves 이 수를 넘으면 <strong>FAST</strong> 다 (레코드 수 = 웨이브 수)
     * @param budgetRatio     직전 계획이 예산의 이 비율을 넘겼으면 개선 예산을 줄인다
     * @param budgetFactor    그때 개선 예산에 곱하는 계수. 기본 0.5 — <strong>절반</strong>
     */
    public record Degrade(@DefaultValue("3") long maxBacklogWaves,
            @DefaultValue("0.8") double budgetRatio,
            @DefaultValue("0.5") double budgetFactor) {
    }

    /**
     * 후보 우선도 점수표 (§6.3, ADR-028). 우선도는 선언이 아니라 <strong>파생</strong>이고,
     * 가중치는 정책이므로 데이터로 둔다 — 정책을 바꾸는 데 배포가 필요하면 그것은 바꿀 수
     * 없는 정책이다.
     *
     * <p>세 번째 사실 <strong>「배송 실패 후 재배송」(+3)</strong> 은 tracking 이 만든다.
     * 그 사실이 도착하기 전에 가중치만 먼저 두지 않는다 — 쓰이지 않는 설정 키는 다음 사람이
     * 측정 없이 믿는 값이 된다. Phase 5 에서 사실과 함께 들어온다.
     *
     * @param promiseRevised 약속을 개정한 주문에 더하는 점수
     * @param requiresCold   냉장 주문에 더하는 점수
     */
    public record Priority(@DefaultValue("2") int promiseRevised,
            @DefaultValue("1") int requiresCold) {
    }

    /**
     * @param defaultStrategy 기본 전략 (§6.6)
     * @param budget          계획 전체의 시간 예산 (§6.7 기본 30초)
     * @param perRouteBudget  라우트 하나의 상한
     * @param staleAfter      이만큼 지난 {@code PLANNING} 은 죽은 것으로 본다 (§5.3 기본 10분)
     * @param recoverBatch    한 번에 회수할 최대 계획 수
     */
    public record Plan(@DefaultValue("sweep-greedy-nn+ls") String defaultStrategy,
            @DefaultValue("30s") Duration budget,
            @DefaultValue("3s") Duration perRouteBudget,
            @DefaultValue("10m") Duration staleAfter,
            @DefaultValue("20") int recoverBatch) {

        public Plan {
            if (defaultStrategy.isBlank()) {
                throw ValidationException.field("dawnline.dispatch.plan.default-strategy",
                        defaultStrategy, "기본 전략 이름은 비어 있을 수 없습니다");
            }
        }
    }

    /**
     * @param roadFactor      직선거리 → 도로거리 계수 (§6.2 기본 1.3)
     * @param averageSpeedKmh 평균 주행 속도 (§6.2 기본 25)
     */
    public record Distance(@DefaultValue("1.3") double roadFactor,
            @DefaultValue("25.0") double averageSpeedKmh) {
    }
}
