package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code dispatch_candidates} 영속화 (DESIGN.md §5.3). */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchPersistenceIT — 계획 후보")
class DispatchPersistenceIT extends DispatchIntegrationTestBase {

    private static final Instant NOW =
            Instant.parse("2026-09-06T01:00:00Z").truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate());
    }

    private static DispatchCandidate candidate(UUID waveId) {
        return DispatchCandidate.load(Ids.newId(), waveId, Ids.newId(), Ids.newId(),
                GeoPoint.of(37.497900, 127.027600), 1_234, 5_678, true, false,
                new TimeWindow(NOW, NOW.plus(Duration.ofHours(4))), 120, 2, NOW);
    }

    @Test
    void 모든_컬럼이_왕복한다() {
        DispatchCandidate saved = candidate(Ids.newId());
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.waveId()).isEqualTo(saved.waveId());
        assertThat(loaded.campId()).isEqualTo(saved.campId());
        assertThat(loaded.zoneId()).isEqualTo(saved.zoneId());
        assertThat(loaded.location().lat()).isEqualTo(saved.location().lat());
        assertThat(loaded.location().lng()).isEqualTo(saved.location().lng());
        assertThat(loaded.weightG()).isEqualTo(1_234);
        assertThat(loaded.volumeCm3()).isEqualTo(5_678);
        assertThat(loaded.requiresCold()).isTrue();
        assertThat(loaded.hazmat()).isFalse();
        assertThat(loaded.promised()).isEqualTo(saved.promised());
        assertThat(loaded.serviceSeconds()).isEqualTo(120);
        assertThat(loaded.priority()).isEqualTo(2);
        assertThat(loaded.status()).isEqualTo(CandidateStatus.PENDING);
    }

    @Test
    void 같은_주문은_두_번_들어가지_않는다() {
        // ON CONFLICT DO NOTHING 이다. 조회 후 저장으로 흉내 내면 동시 수신에서 둘 다 넣는다.
        DispatchCandidate first = candidate(Ids.newId());

        boolean inserted = tx().execute(status -> candidates.insertIfAbsent(first));
        boolean again = tx().execute(status -> candidates.insertIfAbsent(first));

        assertThat(inserted).isTrue();
        assertThat(again).isFalse();
    }

    @Test
    void 웨이브의_계획_대상만_모은다() {
        UUID waveId = Ids.newId();
        DispatchCandidate pending = candidate(waveId);
        DispatchCandidate planned = candidate(waveId);
        DispatchCandidate otherWave = candidate(Ids.newId());
        tx().executeWithoutResult(status -> {
            candidates.insertIfAbsent(pending);
            candidates.insertIfAbsent(planned);
            candidates.insertIfAbsent(otherWave);
        });
        tx().executeWithoutResult(status -> {
            DispatchCandidate found = candidates.findById(planned.orderId()).orElseThrow();
            found.recordPlanResult(CandidateStatus.PLANNED, NOW);
            candidates.update(found);
        });

        List<DispatchCandidate> plannable =
                tx().execute(status -> candidates.findPlannableInWave(waveId));

        assertThat(plannable).extracting(DispatchCandidate::orderId)
                .containsExactly(pending.orderId());
    }

    @Test
    void 취소해도_행과_소속이_남는다() {
        // ADR-026 — 지우면 "주문 X 는 왜 라우트에 없나" 에 답할 수 없다.
        DispatchCandidate saved = candidate(Ids.newId());
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));
        tx().executeWithoutResult(status -> {
            DispatchCandidate found = candidates.findById(saved.orderId()).orElseThrow();
            found.cancel(NOW.plusSeconds(60));
            candidates.update(found);
        });

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.status()).isEqualTo(CandidateStatus.CANCELLED);
        assertThat(loaded.waveId()).isEqualTo(saved.waveId());
        assertThat(loaded.location()).isEqualTo(saved.location());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 계획_대상_조회가_인덱스를_탄다() {
        // ix_cand_wave (wave_id, status). 순차 스캔이면 웨이브마다 전수 스캔이 된다.
        UUID waveId = Ids.newId();
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < 50; i++) {
                candidates.insertIfAbsent(candidate(waveId));
            }
        });

        String plan = tx().execute(status -> String.join("\n",
                (List<String>) entityManager.createNativeQuery("""
                        EXPLAIN SELECT * FROM dispatch_candidates
                         WHERE wave_id = ? AND status = 'PENDING'
                        """).setParameter(1, waveId).getResultList()));

        assertThat(plan).contains("ix_cand_wave");
    }

    @Test
    void 좌표가_저장_정밀도로_왕복한다() {
        // NUMERIC(9,6) 이다. 자르지 않으면 저장 전후 값이 달라져 거리 계산이 미세하게 어긋난다.
        DispatchCandidate saved = DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(), null,
                GeoPoint.of(37.4979009, 127.0276001), 1, 1, false, false,
                new TimeWindow(NOW, NOW.plusSeconds(3600)), 60, 0, NOW);
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(saved));

        DispatchCandidate loaded = tx().execute(status ->
                candidates.findById(saved.orderId()).orElseThrow());

        assertThat(loaded.location().lat()).isEqualTo(37.497901);
        assertThat(loaded.location().lng()).isEqualTo(127.027600);
    }
}
