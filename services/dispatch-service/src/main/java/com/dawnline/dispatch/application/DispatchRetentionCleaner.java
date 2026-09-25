package com.dawnline.dispatch.application;

import com.dawnline.dispatch.application.port.out.DispatchRetention;
import com.dawnline.dispatch.application.port.out.DispatchRetention.PlanRef;
import com.dawnline.dispatch.application.port.out.DispatchRetention.PlanRows;
import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * dispatch 보존 정리 — 일 1회 ([ADR-059](docs/adr/ADR-059-dispatch-retention-is-per-plan.md), DESIGN.md §7.1).
 *
 * <h2>지우는 단위는 계획이다</h2>
 * 표마다 {@code ctid} 배치를 돌리지 않는다. 계획 계열은 FK 가 넷이라 표마다 자르면 부모 쪽마다 {@code NOT EXISTS}
 * 가드가 필요하고, 그 가드가 1,350만 행 표를 본다. 계획 하나를 한 트랜잭션에서 자식부터 지우면 가드가 필요 없고
 * 모든 문장이 기존 인덱스의 앞머리를 탄다(결정 6). 트랜잭션의 상한은 계획 하나다 — 벤치마크 {@code peak} 크기의
 * 계획이면 평상시 약 2.4만 행, 여섯 표가 전부 남은 최악(상한이 걸린 계획)이 약 5.4만 행이다(측정 §4).
 *
 * <h2>나이는 계획의 {@code finished_at} 이다 — 안전한 쪽이 아니다</h2>
 * 후보 · 설명도 자기 나이가 아니라 그 계획의 나이로 지운다. 계획 뒤의 사실(stop 전이 · 재계획 · 재배정)은 배송일
 * 안에 끝나므로 오차는 최대 하루이고 방향은 <em>일찍</em> 지우는 쪽이다(결정 1). 적어 둔 대가다.
 *
 * <h2>후보는 계획이 끝나야 지운다</h2>
 * 재배정 · 재계획 · 취소가 후보를 조인한다. 끝나지 않은 라우트의 후보를 지우면 그 라우트를 고치는 경로가 막힌다 —
 * 조용히 빠지지 않게 가드({@code candidates-expired})를 두었지만, 가드는 결함을 보이게 할 뿐 고치지 않는다.
 * 그래서 조건이 「계획이 30일 지났다」가 아니라 「계획이 30일 지났고 <strong>그 계획의 모든 stop 이 끝났다</strong>」다(결정 3).
 *
 * <h2>걸린 계획은 남기되 센다</h2>
 * 끝나지 않은 계획은 조사 대상이라 남는다(365일 상한까지). 예외는 셈이 있어야 예외다 — 30일(후보 · 설명이 처음
 * 붙잡히는 자리)을 넘긴 그 계획의 수를 매 실행이 센다({@code dawnline_route_plans_stuck}). 세기 전과 실행이 실패한
 * 동안은 {@code NaN} 이다 — 0 은 「걸린 것이 없다」는 주장이다.
 *
 * <h2>실패는 삼키되 보이게</h2>
 * 여섯 표의 {@code dawnline_retention_last_success_age_seconds{table}} 은 <strong>함께 움직인다</strong> — 지우는
 * 단위가 계획이라 한 실행의 성공이 여섯 표의 성공이다. 어느 단계든 실패하면 여섯이 함께 자란다.
 */
public class DispatchRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(DispatchRetentionCleaner.class);

    private final DispatchRetention retention;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration candidates;
    private final Duration explanations;
    private final Duration plans;
    private final Duration cap;
    private final int maxPlansPerRun;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final List<RetentionAges.Table> ages;

    /** 마지막으로 센 걸린 계획 수. 모르면 {@code NaN} 의 비트 — {@link Double#doubleToLongBits} 로 담는다. */
    private final AtomicLong stuck = new AtomicLong(Double.doubleToLongBits(Double.NaN));

    /**
     * 기간 사이의 관계(후보 · 설명 ≤ 계획 ≤ 상한)는 설정 레코드가 기동에서 거부한다 — 여기서는 양수만 본다.
     *
     * @param retention          고르기 · 삭제 · 셈
     * @param transactionManager 계획마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              임계 시각 계산 (불변규칙 12)
     * @param candidates         후보 보존 (기본 30일)
     * @param explanations       설명 보존 (기본 30일)
     * @param plans              계획 계열 보존 (기본 90일)
     * @param cap                상한 (기본 365일)
     * @param maxPlansPerRun     한 실행에서 단계마다 다룰 최대 계획 수
     * @param batchSize          계획 없는 웨이브의 후보를 한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun   그 배치의 한 실행 최대 반복 수
     * @param ages               성공 나이 게이지 — 생성하면서 여섯 표를 등록한다
     * @param meters             {@code dawnline_route_plans_stuck} 을 등록할 레지스트리 — 기동 때 {@code NaN} 으로
     */
    public DispatchRetentionCleaner(DispatchRetention retention, PlatformTransactionManager transactionManager,
            Clock clock, Duration candidates, Duration explanations, Duration plans, Duration cap,
            int maxPlansPerRun, int batchSize, int maxBatchesPerRun, RetentionAges ages, MeterRegistry meters) {

        this.retention = Objects.requireNonNull(retention, "retention");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.candidates = requirePositive(candidates, "candidates");
        this.explanations = requirePositive(explanations, "explanations");
        this.plans = requirePositive(plans, "plans");
        this.cap = requirePositive(cap, "cap");
        if (maxPlansPerRun < 1 || batchSize < 1 || maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("maxPlansPerRun · batchSize · maxBatchesPerRun 은 1 이상이어야 합니다");
        }
        this.maxPlansPerRun = maxPlansPerRun;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        Objects.requireNonNull(ages, "ages");
        // 라벨은 §7.1 보존 표의 첫 열이다 — 이름을 리터럴로 적는다(RetentionTableConsistencyTest 가 그 등록을 찾는다).
        this.ages = List.of(ages.table("dispatch_candidates"), ages.table("plan_explanations"),
                ages.table("route_plans"), ages.table("routes"), ages.table("route_stops"),
                ages.table("route_stop_orders"));
        // 헬퍼가 강한 참조로 잡는다 — 이 정리기가 컨텍스트 밖에서 만들어져도(테스트) 게이지가 GC 로 NaN 이 되지 않는다.
        // 「세기 전 · 실패 중 NaN」이 뜻하는 것은 모름 하나뿐이어야 한다(ADR-060, §13 축 10).
        DawnlineMeters.gauge(Objects.requireNonNull(meters, "meters"), DawnlineMetrics.ROUTE_PLANS_STUCK, this, DispatchRetentionCleaner::stuckPlans);
    }

    /**
     * 일 1회 정리.
     *
     * <p>초기 지연 기본값 15분은 다른 정리(outbox 1분 · {@code processed_events} 5분)와 <strong>어긋나게</strong> 둔
     * 것이다 — 같은 스케줄러 풀을 쓴다(ADR-058 결정 5).
     *
     * <p>예외를 삼킨다. 정리 실패는 용량 문제지 정확성 문제가 아니므로 다음 실행이 이어받으면 된다. 삼킨 실패는
     * 성공 나이 게이지와 걸린 계획 게이지({@code NaN})가 말한다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.dispatch.retention.cleanup-interval-ms:86400000}",
            initialDelayString = "${dawnline.dispatch.retention.cleanup-initial-delay-ms:900000}")
    public void cleanupExpired() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("보존 정리 실패. 다음 실행에서 이어서 지웁니다.", e);
        }
    }

    /**
     * 만료 행을 계획 단위로 지우고 걸린 계획을 센다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * <p>실패하면 걸린 계획 게이지를 {@code NaN} 으로 되돌리고 예외를 그대로 올린다 — 모르는 값을 마지막 값으로
     * 남겨 두지 않는다.
     *
     * @return 이번 실행의 결과
     */
    public Deleted deleteExpired() {
        try {
            return run();
        } catch (RuntimeException e) {
            stuck.set(Double.doubleToLongBits(Double.NaN));
            throw e;
        }
    }

    private Deleted run() {
        Instant now = clock.instant();

        // 1. 설명 · 후보 — 계획이 끝났을 때만, 그 계획의 나이로(결정 3).
        int explanationRows = perPlan("plan_explanations",
                retention.settledPlansWithExplanationsFinishedBefore(now.minus(explanations), maxPlansPerRun),
                retention::deleteExplanations);
        int candidateRows = perPlan("dispatch_candidates",
                retention.settledPlansWithCandidatesFinishedBefore(now.minus(candidates), maxPlansPerRun),
                retention::deleteCandidates);

        // 2. 계획 계열 — 계획 하나를 한 트랜잭션에서, 자식부터(결정 6).
        int deletedPlans = perPlan("route_plans",
                retention.settledPlansFinishedBefore(now.minus(plans), maxPlansPerRun), this::deletePlan);

        // 3. 상한 — 종결과 무관하게 계열째, 그리고 계획 없는 웨이브의 후보(결정 5). 정리이지 정책이 아니다.
        int cappedPlans = perPlan("route_plans(상한)",
                retention.plansAgedBefore(now.minus(cap), maxPlansPerRun), this::deletePlan);
        int orphanCandidates = deleteOrphanCandidates(now.minus(cap));

        ages.forEach(RetentionAges.Table::succeeded);

        // 4. 걸린 계획 — 후보 · 설명이 처음 붙잡히는 임계에서 센다(결정 4).
        Duration first = candidates.compareTo(explanations) <= 0 ? candidates : explanations;
        long stuckPlans = Objects.requireNonNull(
                transactions.execute(status -> retention.countStuckPlansAgedBefore(now.minus(first))), "stuck");
        stuck.set(Double.doubleToLongBits(stuckPlans));
        if (stuckPlans > 0) {
            log.info("보존 기간({})을 넘겼는데 끝나지 않은 계획 {}건 — 계획 상태면 RB-04, stop 이면 delivery.status 의 DLQ(RB-05).",
                    first, stuckPlans);
        }
        return new Deleted(explanationRows, candidateRows, deletedPlans, cappedPlans, orphanCandidates, stuckPlans);
    }

    /**
     * {@code dawnline_route_plans_stuck} 게이지 값.
     *
     * @return 마지막으로 센 걸린 계획 수. 세기 전이거나 마지막 실행이 실패했으면 {@code NaN}
     */
    public double stuckPlans() {
        return Double.longBitsToDouble(stuck.get());
    }

    /** 계열째 지우고 계획 수(0 또는 1)를 돌려준다 — 0 이면 다른 인스턴스가 먼저 지웠다. */
    private int deletePlan(PlanRef plan) {
        PlanRows rows = retention.deletePlan(plan);
        log.debug("계획 {} 을 계열째 지웠다: {}행 ({})", plan.planId(), rows.total(), rows);
        return rows.plans();
    }

    /**
     * 고른 계획마다 트랜잭션 하나. 계획 수가 상한에 닿았으면 남은 것은 다음 실행이 지운다.
     *
     * @return {@code delete} 가 돌려준 수의 합
     */
    private int perPlan(String step, List<PlanRef> selected, ToIntFunction<PlanRef> delete) {
        int total = 0;
        for (PlanRef plan : selected) {
            Integer rows = transactions.execute(status -> delete.applyAsInt(plan));
            total += rows == null ? 0 : rows;
        }
        if (selected.size() >= maxPlansPerRun) {
            log.info("{} — 계획 {}개 {}건. 한 실행 상한({}계획)에 걸려 남은 계획은 다음 실행이 지운다.",
                    step, selected.size(), total, maxPlansPerRun);
        } else if (!selected.isEmpty()) {
            log.info("{} — 계획 {}개 {}건", step, selected.size(), total);
        }
        return total;
    }

    /** 계획 없는 웨이브의 후보 — {@code ctid} 배치를 배치마다 커밋한다(ADR-058 결정 5 의 기존 패턴). */
    private int deleteOrphanCandidates(Instant threshold) {
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = transactions.execute(
                    status -> retention.deleteOrphanCandidatesUpdatedBefore(threshold, batchSize));
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < batchSize) {
                if (total > 0) {
                    log.info("계획 없는 웨이브의 후보 {}건 삭제 (임계 {}) — 상한이다", total, threshold);
                }
                return total;
            }
        }
        log.info("계획 없는 웨이브의 후보 {}건 삭제 (임계 {}). 한 실행 상한({}배치)에 걸려 남은 행은 다음 실행이 지운다.",
                total, threshold, maxBatchesPerRun);
        return total;
    }

    /**
     * 한 실행의 결과.
     *
     * @param explanations     설명 단계가 지운 행 수
     * @param candidates       후보 단계가 지운 행 수
     * @param plans            계획 계열 단계가 지운 계획 수
     * @param cappedPlans      상한이 지운 계획 수
     * @param orphanCandidates 상한이 지운, 계획 없는 웨이브의 후보 행 수
     * @param stuckPlans       걸린 계획 수 — 게이지의 값
     */
    public record Deleted(int explanations, int candidates, int plans, int cappedPlans, int orphanCandidates,
            long stuckPlans) {
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 은 양수여야 합니다: " + value);
        }
        return value;
    }
}
