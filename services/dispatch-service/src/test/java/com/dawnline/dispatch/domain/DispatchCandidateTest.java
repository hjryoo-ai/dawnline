package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DispatchCandidateTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW, NOW.plus(Duration.ofHours(4)));

    private static DispatchCandidate loaded() {
        return DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(),
                GeoPoint.of(37.4979, 127.0276), 1_000, 2_000, false, false, WINDOW, 90, 0, NOW);
    }

    @Test
    void 적재된_후보는_PENDING_이다() {
        DispatchCandidate candidate = loaded();

        assertThat(candidate.status()).isEqualTo(CandidateStatus.PENDING);
        assertThat(candidate.version()).isZero();
        assertThat(candidate.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 계획_결과를_반영한다() {
        DispatchCandidate candidate = loaded();

        assertThat(candidate.recordPlanResult(CandidateStatus.PLANNED, NOW.plusSeconds(60))).isTrue();
        assertThat(candidate.status()).isEqualTo(CandidateStatus.PLANNED);
        assertThat(candidate.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void 늦게_온_판정은_무시한다() {
        // 두 판정은 같은 지점이다 — 늦게 온 쪽이 이기면 결과가 도착 순서에 달린다.
        DispatchCandidate candidate = loaded();
        candidate.recordPlanResult(CandidateStatus.PLANNED, NOW);

        assertThat(candidate.recordPlanResult(CandidateStatus.UNASSIGNED, NOW.plusSeconds(60)))
                .isFalse();
        assertThat(candidate.status()).isEqualTo(CandidateStatus.PLANNED);
    }

    @Test
    void 계획_결과는_두_상태뿐이다() {
        assertThatThrownBy(() -> loaded().recordPlanResult(CandidateStatus.CANCELLED, NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 취소는_행을_남긴다() {
        // ADR-026 — 지우면 "주문 X 는 왜 라우트에 없나" 에 답할 수 없다.
        DispatchCandidate candidate = loaded();
        candidate.recordPlanResult(CandidateStatus.PLANNED, NOW);

        assertThat(candidate.cancel(NOW.plusSeconds(60))).isTrue();
        assertThat(candidate.status()).isEqualTo(CandidateStatus.CANCELLED);
        assertThat(candidate.waveId()).as("소속은 지우지 않는다").isNotNull();
    }

    @Test
    void 중복_취소는_사실을_바꾸지_않는다() {
        DispatchCandidate candidate = loaded();
        candidate.cancel(NOW);

        assertThat(candidate.cancel(NOW.plusSeconds(60))).isFalse();
        assertThat(candidate.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void 권역이_없을_수_있다() {
        DispatchCandidate candidate = DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(),
                null, GeoPoint.of(37.5, 127.0), 1, 1, false, false, WINDOW, 60, 0, NOW);

        assertThat(candidate.zoneId()).isEmpty();
    }

    @Test
    void 음수_화물은_거부한다() {
        assertThatThrownBy(() -> DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(), null,
                GeoPoint.of(37.5, 127.0), -1, 1, false, false, WINDOW, 60, 0, NOW))
                .isInstanceOf(ValidationException.class);
    }
}
