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
 */
@ConfigurationProperties(prefix = "dawnline.dispatch")
public record DispatchProperties(@DefaultValue Plan plan, @DefaultValue Distance distance,
        @DefaultValue Priority priority, @DefaultValue Degrade degrade) {

    /**
     * 열화 모드 임계 (§6.7, ADR-034). 기본값은 설계서의 두 수 그대로다 —
     * <strong>랙 3 웨이브</strong>, <strong>예산의 80%</strong>.
     *
     * <p>설정으로 두는 이유는 이 둘이 정책이기 때문이다. 캠프 규모와 러너 사양에 따라 "밀렸다"
     * 의 뜻이 달라지고, 임계를 바꾸는 데 배포가 필요하면 성수기 한복판에서 바꿀 수 없다.
     *
     * @param maxBacklogWaves 이 수를 넘으면 열화한다 (레코드 수 = 웨이브 수)
     * @param budgetRatio     직전 계획이 예산의 이 비율을 넘겼으면 열화한다
     */
    public record Degrade(@DefaultValue("3") long maxBacklogWaves,
            @DefaultValue("0.8") double budgetRatio) {
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
