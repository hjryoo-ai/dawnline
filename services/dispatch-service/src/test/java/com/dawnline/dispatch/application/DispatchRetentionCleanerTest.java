package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.dispatch.application.port.out.DispatchRetention;
import com.dawnline.dispatch.application.port.out.DispatchRetention.PlanRef;
import com.dawnline.dispatch.application.port.out.DispatchRetention.PlanRows;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.retention.ManualClock;
import com.dawnline.messaging.retention.RetentionAges;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 보존 정리의 단계 · 임계 · 트랜잭션 단위 (ADR-059). DB 는 {@code DispatchRetentionIT} 가 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DispatchRetentionCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RecordingRetention retention = new RecordingRetention();
    private final CountingTransactionManager transactions = new CountingTransactionManager();
    private final ManualClock ageClock = new ManualClock(NOW);
    private final SimpleMeterRegistry ageMeters = new SimpleMeterRegistry();
    private final RetentionAges ages = new RetentionAges(ageMeters, ageClock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private DispatchRetentionCleaner cleaner() {
        return cleaner(Duration.ofDays(30), Duration.ofDays(30), 200);
    }

    /**
     * 마지막으로 만든 정리기 — 붙잡아 둔다. 게이지는 상태 객체를 약한 참조로 잡으므로(Micrometer) 아무도 들고 있지
     * 않으면 GC 뒤에 {@code NaN} 이다 — 그러면 「NaN 이다」를 보는 검사가 아무것도 검사하지 않는다.
     */
    private DispatchRetentionCleaner cleaner;

    private DispatchRetentionCleaner cleaner(Duration candidates, Duration explanations, int maxPlans) {
        cleaner = new DispatchRetentionCleaner(retention, transactions, CLOCK, candidates, explanations,
                Duration.ofDays(90), Duration.ofDays(365), maxPlans, 1000, 10, ages, meters);
        return cleaner;
    }

    private double stuckGauge() {
        return meters.get(DispatchRetentionCleaner.STUCK).gauge().value();
    }

    @Test
    void 단계는_설명_후보_계열_상한_셈의_순서다() {
        // 설명·후보가 계열보다 먼저다 — 계열 삭제는 남은 설명·후보도 지우지만, 30일 단계를 거치지 않으면
        // 두 표가 90일까지 남는다. 셈은 맨 뒤다(지운 뒤의 표를 센다).
        cleaner().deleteExpired();

        assertThat(retention.calls).containsExactly("selectExplanations", "selectCandidates", "selectSettled",
                "selectAged", "orphans", "count");
    }

    @Test
    void 임계는_단계마다의_보존_기간이다() {
        cleaner().deleteExpired();

        assertThat(retention.thresholds).containsExactly(
                NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(90)),
                NOW.minus(Duration.ofDays(365)), NOW.minus(Duration.ofDays(365)), NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void 고른_계획마다_트랜잭션_하나다() {
        // 트랜잭션의 상한이 「계획 하나」다(결정 6). 계획 셋을 한 트랜잭션에 묶으면 peak 계획 셋이 9만 행이 된다.
        retention.settled = plans(3);

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.plans()).isEqualTo(3);
        assertThat(retention.deletedPlans).hasSize(3);
        // 계획 셋 + 계획 없는 웨이브의 후보 배치 하나 + 셈 하나.
        assertThat(transactions.opened).isEqualTo(3 + 1 + 1);
    }

    @Test
    void 설명과_후보는_계획_단위로_따로_지운다() {
        retention.withExplanations = plans(2);
        retention.withCandidates = plans(1);

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(retention.explanationPlans).isEqualTo(retention.withExplanations);
        assertThat(retention.candidatePlans).isEqualTo(retention.withCandidates);
        assertThat(deleted.explanations()).isEqualTo(2 * RecordingRetention.EXPLANATIONS_PER_PLAN);
        assertThat(deleted.candidates()).isEqualTo(RecordingRetention.CANDIDATES_PER_PLAN);
    }

    @Test
    void 한_실행의_계획_수에_상한이_있다() {
        // 고르기가 상한만큼만 돌려준다 — 남은 계획은 다음 실행이 고른다.
        cleaner(Duration.ofDays(30), Duration.ofDays(30), 2).deleteExpired();

        assertThat(retention.limits).containsOnly(2, 1000).contains(2);
    }

    @Test
    void 상한은_종결과_무관하게_계열째_지운다() {
        retention.aged = plans(2);

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.cappedPlans()).isEqualTo(2);
        assertThat(retention.deletedPlans).isEqualTo(retention.aged);
    }

    @Test
    void 다른_인스턴스가_먼저_지운_계획은_세지_않는다() {
        retention.settled = plans(2);
        retention.alreadyGone = retention.settled.getFirst();

        assertThat(cleaner().deleteExpired().plans()).isEqualTo(1);
    }

    @Test
    void 계획_없는_웨이브의_후보는_덜_찬_배치에서_멈춘다() {
        retention.orphanRows = 2500;

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.orphanCandidates()).isEqualTo(2500);
        assertThat(retention.calls.stream().filter("orphans"::equals)).as("1000·1000·500 세 배치").hasSize(3);
    }

    // --- 걸린 계획 (결정 4) --------------------------------------------------------------

    @Test
    void 걸린_계획_게이지는_세기_전에_NaN_이다() {
        cleaner();

        // 0 은 「걸린 것이 없다」는 주장이다. 아직 세지 않았으면 모른다.
        assertThat(stuckGauge()).isNaN();
    }

    @Test
    void 걸린_계획을_센다() {
        retention.stuck = 4;

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.stuckPlans()).isEqualTo(4);
        assertThat(stuckGauge()).isEqualTo(4.0);
    }

    @Test
    void 걸린_계획은_후보와_설명_중_짧은_쪽의_임계로_센다() {
        // 걸린 것이 처음 붙잡히는 자리다 — 90일로 세면 그 사이에 붙잡힌 후보를 아무도 세지 않는다.
        cleaner(Duration.ofDays(20), Duration.ofDays(30), 200).deleteExpired();

        assertThat(retention.thresholds.getLast()).isEqualTo(NOW.minus(Duration.ofDays(20)));
    }

    @Test
    void 실패하면_걸린_계획_게이지가_NaN_으로_돌아간다() {
        DispatchRetentionCleaner cleaner = cleaner();
        retention.stuck = 4;
        cleaner.deleteExpired();
        retention.failSettled = true;

        assertThatThrownBy(cleaner::deleteExpired).isInstanceOf(IllegalStateException.class);

        // 멈춘 값은 건강해 보인다 — 모르면 모른다고 한다.
        assertThat(stuckGauge()).isNaN();
    }

    // --- 성공 나이 (ADR-058 결정 6) ------------------------------------------------------

    @Test
    void 여섯_표를_기동_때_등록한다() {
        cleaner();

        // ages.table(x) 는 없으면 등록하므로 등록의 증거가 못 된다 — 레지스트리에서 찾는다.
        assertThat(ageMeters.find(MessagingMetrics.RETENTION_LAST_SUCCESS_AGE).gauges())
                .extracting(gauge -> gauge.getId().getTag(MessagingMetrics.TAG_TABLE))
                .containsExactlyInAnyOrderElementsOf(tables());
    }

    @Test
    void 어느_단계든_실패하면_여섯_표의_나이가_함께_자란다() {
        DispatchRetentionCleaner cleaner = cleaner();
        retention.failSettled = true;
        ageClock.advance(Duration.ofDays(2));

        cleaner.cleanupExpired();

        // 지우는 단위가 계획이라 한 실행의 성공이 여섯 표의 성공이다 — 실패도 그렇다.
        assertThat(tables())
                .allSatisfy(table -> assertThat(ages.table(table).ageSeconds()).isEqualTo(2 * 86400.0));
    }

    @Test
    void 끝까지_돌면_여섯_표의_나이가_0_으로_돌아간다() {
        DispatchRetentionCleaner cleaner = cleaner();
        ageClock.advance(Duration.ofDays(2));

        cleaner.cleanupExpired();

        assertThat(tables()).allSatisfy(table -> assertThat(ages.table(table).ageSeconds()).isZero());
    }

    @Test
    void 스케줄_진입점은_예외를_삼킨다() {
        retention.failSettled = true;

        cleaner().cleanupExpired();

        // 예외가 밖으로 나가면 스케줄러 스레드가 그 작업을 더는 돌리지 않는다.
        assertThat(retention.calls).containsExactly("selectExplanations", "selectCandidates", "selectSettled");
    }

    // ------------------------------------------------------------------------------------

    private static List<String> tables() {
        return List.of("dispatch_candidates", "plan_explanations", "route_plans", "routes", "route_stops",
                "route_stop_orders");
    }

    private static List<PlanRef> plans(int count) {
        return IntStream.range(0, count).mapToObj(i -> new PlanRef(UUID.randomUUID(), UUID.randomUUID())).toList();
    }

    /** 부른 순서 · 임계 · 상한을 적는 가짜. */
    private static final class RecordingRetention implements DispatchRetention {

        static final int EXPLANATIONS_PER_PLAN = 5000;
        static final int CANDIDATES_PER_PLAN = 3750;

        final List<String> calls = new ArrayList<>();
        final List<Instant> thresholds = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        final List<PlanRef> explanationPlans = new ArrayList<>();
        final List<PlanRef> candidatePlans = new ArrayList<>();
        final List<PlanRef> deletedPlans = new ArrayList<>();
        List<PlanRef> withExplanations = List.of();
        List<PlanRef> withCandidates = List.of();
        List<PlanRef> settled = List.of();
        List<PlanRef> aged = List.of();
        PlanRef alreadyGone;
        int orphanRows;
        long stuck;
        boolean failSettled;

        @Override
        public List<PlanRef> settledPlansWithExplanationsFinishedBefore(Instant finishedBefore, int limit) {
            return select("selectExplanations", finishedBefore, limit, withExplanations);
        }

        @Override
        public int deleteExplanations(PlanRef plan) {
            explanationPlans.add(plan);
            return EXPLANATIONS_PER_PLAN;
        }

        @Override
        public List<PlanRef> settledPlansWithCandidatesFinishedBefore(Instant finishedBefore, int limit) {
            return select("selectCandidates", finishedBefore, limit, withCandidates);
        }

        @Override
        public int deleteCandidates(PlanRef plan) {
            candidatePlans.add(plan);
            return CANDIDATES_PER_PLAN;
        }

        @Override
        public List<PlanRef> settledPlansFinishedBefore(Instant finishedBefore, int limit) {
            List<PlanRef> selected = select("selectSettled", finishedBefore, limit, settled);
            if (failSettled) {
                throw new IllegalStateException("DB 가 죽었다");
            }
            return selected;
        }

        @Override
        public List<PlanRef> plansAgedBefore(Instant agedBefore, int limit) {
            return select("selectAged", agedBefore, limit, aged);
        }

        @Override
        public PlanRows deletePlan(PlanRef plan) {
            deletedPlans.add(plan);
            return new PlanRows(3750, 2100, 30, 0, 0, plan.equals(alreadyGone) ? 0 : 1);
        }

        @Override
        public int deleteOrphanCandidatesUpdatedBefore(Instant updatedBefore, int limit) {
            calls.add("orphans");
            if (!calls.subList(0, calls.size() - 1).contains("orphans")) {
                thresholds.add(updatedBefore);
            }
            limits.add(limit);
            int rows = Math.min(orphanRows, limit);
            orphanRows -= rows;
            return rows;
        }

        @Override
        public long countStuckPlansAgedBefore(Instant agedBefore) {
            calls.add("count");
            thresholds.add(agedBefore);
            return stuck;
        }

        private List<PlanRef> select(String call, Instant threshold, int limit, List<PlanRef> plans) {
            calls.add(call);
            thresholds.add(threshold);
            limits.add(limit);
            return plans.subList(0, Math.min(limit, plans.size()));
        }
    }

    /** 연 트랜잭션 수를 센다. */
    private static final class CountingTransactionManager implements PlatformTransactionManager {

        int opened;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            opened++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
