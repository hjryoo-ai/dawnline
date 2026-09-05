package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.LoadCandidateUseCase;
import com.dawnline.dispatch.application.port.in.PlannedOrderSnapshot;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LoadCandidateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW, NOW.plus(Duration.ofHours(4)));

    /** 메모리 저장소. {@code ON CONFLICT DO NOTHING} 과 같은 의미를 흉내 낸다. */
    private static final class InMemory implements DispatchCandidateRepository {

        private final Map<UUID, DispatchCandidate> rows = new LinkedHashMap<>();

        @Override
        public boolean insertIfAbsent(DispatchCandidate candidate) {
            return rows.putIfAbsent(candidate.orderId(), candidate) == null;
        }

        @Override
        public Optional<DispatchCandidate> findById(UUID orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public List<DispatchCandidate> findPlannableInWave(UUID waveId) {
            List<DispatchCandidate> found = new ArrayList<>();
            rows.values().stream()
                    .filter(candidate -> candidate.waveId().equals(waveId))
                    .filter(candidate -> candidate.status().isPlannable())
                    .forEach(found::add);
            return found;
        }

        @Override
        public void update(DispatchCandidate candidate) {
            rows.put(candidate.orderId(), candidate);
        }
    }

    private final InMemory repository = new InMemory();
    private final LoadCandidateService service =
            new LoadCandidateService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static PlannedOrderSnapshot snapshot(UUID orderId) {
        return new PlannedOrderSnapshot(orderId, Ids.newId(), Ids.newId(), Ids.newId(),
                GeoPoint.of(37.4979, 127.0276), 1_000, 2_000, false, false, WINDOW, 90, 0);
    }

    @Test
    void 스냅샷을_그대로_적재한다() {
        PlannedOrderSnapshot snapshot = snapshot(Ids.newId());

        assertThat(service.load(snapshot)).isEqualTo(LoadCandidateUseCase.Outcome.LOADED);
        assertThat(repository.findById(snapshot.orderId())).hasValueSatisfying(candidate -> {
            assertThat(candidate.status()).isEqualTo(CandidateStatus.PENDING);
            assertThat(candidate.waveId()).isEqualTo(snapshot.waveId());
            assertThat(candidate.location()).isEqualTo(snapshot.location());
            assertThat(candidate.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 같은_주문이_두_번_와도_한_행이다() {
        // processed_events 와 함께 두 겹이다 — 멱등 기록이 14일 뒤 정리돼도 이쪽은 남는다.
        PlannedOrderSnapshot snapshot = snapshot(Ids.newId());
        service.load(snapshot);

        assertThat(service.load(snapshot)).isEqualTo(LoadCandidateUseCase.Outcome.DUPLICATE);
        assertThat(repository.findPlannableInWave(snapshot.waveId())).hasSize(1);
    }

    @Test
    void 재전달은_스냅샷을_덮어쓰지_않는다() {
        // 첫 번째가 계획의 근거였고, 두 번째가 같은 내용이라는 보장이 없다.
        UUID orderId = Ids.newId();
        PlannedOrderSnapshot first = snapshot(orderId);
        service.load(first);

        PlannedOrderSnapshot changed = new PlannedOrderSnapshot(orderId, first.waveId(),
                first.campId(), first.zoneId(), GeoPoint.of(37.0, 127.9), 9_999, 9_999,
                true, true, WINDOW, 90, 5);
        service.load(changed);

        assertThat(repository.findById(orderId)).hasValueSatisfying(candidate -> {
            assertThat(candidate.weightG()).isEqualTo(1_000);
            assertThat(candidate.requiresCold()).isFalse();
        });
    }

    @Test
    void 웨이브별로_계획_대상을_모은다() {
        PlannedOrderSnapshot first = snapshot(Ids.newId());
        PlannedOrderSnapshot sameWave = new PlannedOrderSnapshot(Ids.newId(), first.waveId(),
                first.campId(), null, GeoPoint.of(37.5, 127.0), 1, 1, false, false, WINDOW, 60, 0);
        service.load(first);
        service.load(sameWave);
        service.load(snapshot(Ids.newId()));

        assertThat(repository.findPlannableInWave(first.waveId())).hasSize(2);
    }

    @Test
    void 약속창을_저장_정밀도로_자른다() {
        // PostgreSQL TIMESTAMPTZ 는 마이크로초다. 자르지 않으면 저장 전후 값이 달라진다.
        Instant nanos = Instant.parse("2026-09-06T01:00:00.123456789Z");
        PlannedOrderSnapshot snapshot = new PlannedOrderSnapshot(Ids.newId(), Ids.newId(),
                Ids.newId(), null, GeoPoint.of(37.5, 127.0), 1, 1, false, false,
                new TimeWindow(nanos, nanos.plusSeconds(3600)), 60, 0);

        assertThat(snapshot.promised().start())
                .isEqualTo(Instant.parse("2026-09-06T01:00:00.123456Z"));
    }
}
