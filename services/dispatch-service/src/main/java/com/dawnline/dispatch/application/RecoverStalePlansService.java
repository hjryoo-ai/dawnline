package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.domain.RoutePlan;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 죽은 인스턴스가 남긴 {@code PLANNING} 계획을 되살린다 (DESIGN.md §5.3).
 *
 * <h2>왜 필요한가</h2>
 * 계획 중 인스턴스가 죽으면 계획은 {@code PLANNING} 으로 남고, {@code wave_id} 가 UNIQUE 라
 * <strong>다시 시도할 수도 없다</strong> — 그 웨이브의 주문은 어떤 이벤트도 받지 못하고 영원히
 * 멈춘다. 이 스케줄러가 그 유일한 출구다.
 *
 * <p>결과 쓰기는 계획 단위 트랜잭션이므로 부분 결과가 발행되지 않는다(§5.3). 즉 되돌려 다시
 * 돌리는 것이 안전하다 — 죽은 시도가 남긴 것은 상태뿐이다.
 *
 * <p>회수와 재실행을 <strong>따로</strong> 한다. 되돌리기는 짧은 트랜잭션이고 재실행은 길다.
 * 한 트랜잭션에 묶으면 계획 하나가 실패할 때 회수까지 롤백되어 다음 주기에 같은 일이 반복된다.
 */
public class RecoverStalePlansService {

    private static final Logger log = LoggerFactory.getLogger(RecoverStalePlansService.class);

    private final RoutePlanRepository plans;
    private final RunPlanUseCase runPlan;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration staleAfter;
    private final int batchSize;

    /**
     * @param plans              계획 저장소
     * @param runPlan            재실행할 유스케이스
     * @param transactionManager 회수 트랜잭션
     * @param clock              시각 출처 (불변규칙 12)
     * @param staleAfter         이만큼 지난 {@code PLANNING} 은 죽은 것으로 본다 (§5.3 기본 10분)
     * @param batchSize          한 번에 회수할 최대 개수
     */
    public RecoverStalePlansService(RoutePlanRepository plans, RunPlanUseCase runPlan,
            PlatformTransactionManager transactionManager, Clock clock, Duration staleAfter,
            int batchSize) {

        this.plans = Objects.requireNonNull(plans, "plans");
        this.runPlan = Objects.requireNonNull(runPlan, "runPlan");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("정체 판정 시간은 양수여야 합니다: " + staleAfter);
        }
        this.batchSize = batchSize;
    }

    /** 주기 실행 (§5.3). */
    @Scheduled(
            fixedDelayString = "${dawnline.dispatch.plan.recover-interval-ms:60000}",
            initialDelayString = "${dawnline.dispatch.plan.recover-initial-delay-ms:60000}")
    public void recoverStalePlans() {
        recover();
    }

    /**
     * 회수하고 재실행한다.
     *
     * @return 재실행한 계획 수
     */
    public int recover() {
        List<RoutePlan> stale = transactions.execute(status ->
                plans.findStalePlanning(clock.instant().minus(staleAfter), batchSize));
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        log.warn("정체된 계획 {}건을 회수합니다 (기준 {})", stale.size(), staleAfter);

        int rerun = 0;
        for (RoutePlan plan : stale) {
            transactions.executeWithoutResult(status -> {
                RoutePlan fresh = plans.findById(plan.id()).orElseThrow();
                fresh.requeue(clock.instant());
                plans.update(fresh);
            });
            // 좌표는 계획 행에 있다 — 이 경로는 wave.closed 를 다시 받지 않는다.
            runPlan.run(RunPlanCommand.rerun(plan.waveId(), plan.campId()));
            rerun++;
        }
        return rerun;
    }
}
