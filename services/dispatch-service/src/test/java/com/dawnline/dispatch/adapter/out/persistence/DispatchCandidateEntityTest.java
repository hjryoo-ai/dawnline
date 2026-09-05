package com.dawnline.dispatch.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code dispatch_candidates} 왕복 매핑 (DESIGN.md §5.3). 실제 DB 왕복은
 * {@code DispatchPersistenceIT} 가 본다 — 여기서 보는 것은 <em>도메인과 행 사이에서 무엇이
 * 보존되고 무엇이 보존되지 않는가</em> 다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DispatchCandidateEntityTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW, NOW.plus(Duration.ofHours(4)));
    private static final GeoPoint LOCATION = GeoPoint.of(37.4979, 127.0276);

    private static DispatchCandidate loaded() {
        return DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(),
                LOCATION, 1_200, 8_000, true, false, WINDOW, 90, 3, NOW);
    }

    @Test
    void 스냅샷이_손실_없이_왕복한다() {
        DispatchCandidate candidate = loaded();

        DispatchCandidate restored = DispatchCandidateEntity.from(candidate).toDomain();

        assertThat(restored.orderId()).isEqualTo(candidate.orderId());
        assertThat(restored.waveId()).isEqualTo(candidate.waveId());
        assertThat(restored.campId()).isEqualTo(candidate.campId());
        assertThat(restored.zoneId()).isEqualTo(candidate.zoneId());
        assertThat(restored.location()).isEqualTo(LOCATION);
        assertThat(restored.weightG()).isEqualTo(1_200);
        assertThat(restored.volumeCm3()).isEqualTo(8_000);
        assertThat(restored.requiresCold()).isTrue();
        assertThat(restored.hazmat()).isFalse();
        assertThat(restored.promised()).isEqualTo(WINDOW);
        assertThat(restored.serviceSeconds()).isEqualTo(90);
        assertThat(restored.priority()).isEqualTo(3);
        assertThat(restored.status()).isEqualTo(CandidateStatus.PENDING);
        assertThat(restored.createdAt()).isEqualTo(NOW);
        assertThat(restored.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void 권역이_없는_후보도_왕복한다() {
        // zone_id 는 nullable 이다 — 권역 밖 주소가 캠프 직할로 들어오는 경우가 있다(§5.2).
        DispatchCandidate candidate = DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(),
                null, LOCATION, 1_000, 2_000, false, false, WINDOW, 60, 0, NOW);

        DispatchCandidate restored = DispatchCandidateEntity.from(candidate).toDomain();

        assertThat(restored.zoneId()).isEmpty();
    }

    @Test
    void 좌표는_소수점_여섯_자리로_잘려_저장된다() {
        // NUMERIC(9,6) 이 컬럼 타입이다(불변규칙 9). 일곱 번째 자리를 들고 있다가 DB 에서
        // 반올림되면, 같은 후보가 저장 전후로 다른 좌표를 갖는다.
        DispatchCandidate candidate = DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(),
                Ids.newId(), GeoPoint.of(37.49791234, 127.02765678), 1_000, 2_000, false, false,
                WINDOW, 60, 0, NOW);

        GeoPoint restored = DispatchCandidateEntity.from(candidate).toDomain().location();

        assertThat(restored.lat()).isEqualTo(37.497912);
        assertThat(restored.lng()).isEqualTo(127.027657);
    }

    @Test
    void 상태_전이만_반영하고_스냅샷은_건드리지_않는다() {
        // 계획의 근거가 사후에 바뀌면 설명 조회(§6.9)가 거짓말을 한다.
        DispatchCandidate candidate = loaded();
        DispatchCandidateEntity entity = DispatchCandidateEntity.from(candidate);
        Instant later = NOW.plusSeconds(300);
        candidate.recordPlanResult(CandidateStatus.PLANNED, later);

        entity.apply(candidate);
        DispatchCandidate restored = entity.toDomain();

        assertThat(restored.status()).isEqualTo(CandidateStatus.PLANNED);
        assertThat(restored.updatedAt()).isEqualTo(later);
        assertThat(restored.createdAt()).isEqualTo(NOW);
        assertThat(restored.location()).isEqualTo(LOCATION);
        assertThat(restored.promised()).isEqualTo(WINDOW);
    }

    @Test
    void 다른_주문의_상태는_반영하지_않는다() {
        DispatchCandidateEntity entity = DispatchCandidateEntity.from(loaded());

        assertThatThrownBy(() -> entity.apply(loaded()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다른 주문의 상태는 반영하지 않습니다");
    }

    @Test
    void 행_식별자를_노출한다() {
        DispatchCandidate candidate = loaded();
        UUID orderId = candidate.orderId();

        assertThat(DispatchCandidateEntity.from(candidate).orderId()).isEqualTo(orderId);
    }
}
