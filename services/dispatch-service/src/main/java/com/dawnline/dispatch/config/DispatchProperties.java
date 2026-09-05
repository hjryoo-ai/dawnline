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
 */
@ConfigurationProperties(prefix = "dawnline.dispatch")
public record DispatchProperties(@DefaultValue Plan plan, @DefaultValue Distance distance) {

    /**
     * @param defaultStrategy 기본 전략 (§6.6)
     * @param budget          계획 전체의 시간 예산 (§6.7 기본 30초)
     * @param perRouteBudget  라우트 하나의 상한
     * @param staleAfter      이만큼 지난 {@code PLANNING} 은 죽은 것으로 본다 (§5.3 기본 10분)
     * @param recoverBatch    한 번에 회수할 최대 계획 수
     */
    public record Plan(@DefaultValue("sweep-greedy-nn") String defaultStrategy,
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
