package com.dawnline.dispatch.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link JpaDispatchCandidateRepository} 가 만드는 SQL·JPQL.
 *
 * <p>실행 결과는 {@code CandidateLoadingIT} 가 실물 PostgreSQL 로 본다. 여기서 보는 것은
 * <em>어떤 문장이 만들어지는가</em> 다 — 멱등이 {@code ON CONFLICT} 에 걸려 있고 인덱스 사용이
 * 술어를 리터럴로 적는 데 걸려 있으므로, 그 두 가지는 DB 없이도 확인할 수 있고 확인해야 한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JpaDispatchCandidateRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW, NOW.plus(Duration.ofHours(4)));

    private EntityManager entityManager;
    private JpaDispatchCandidateRepository repository;
    private Query nativeQuery;
    private ArgumentCaptor<String> sql;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        repository = new JpaDispatchCandidateRepository(entityManager);
        sql = ArgumentCaptor.forClass(String.class);

        nativeQuery = mock(Query.class);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);

        TypedQuery<DispatchCandidateEntity> typed = mock(TypedQuery.class);
        when(typed.setParameter(anyString(), any())).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.of());
        when(entityManager.createQuery(anyString(), eq(DispatchCandidateEntity.class)))
                .thenReturn(typed);
    }

    private static DispatchCandidate candidate() {
        return DispatchCandidate.load(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(),
                GeoPoint.of(37.4979, 127.0276), 1_200, 8_000, false, false, WINDOW, 90, 0, NOW);
    }

    @Test
    void 삽입은_ON_CONFLICT_DO_NOTHING_으로_멱등하다() {
        // 조회 후 저장으로 흉내 내면 동시 소비자 둘이 모두 "없다" 를 보고 하나가 PK 위반으로
        // 죽는다 — 그 재시도가 DLQ 로 간다(ADR-018).
        when(nativeQuery.executeUpdate()).thenReturn(1);

        assertThat(repository.insertIfAbsent(candidate())).isTrue();

        org.mockito.Mockito.verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO dispatch_candidates")
                .contains("ON CONFLICT (order_id) DO NOTHING");
    }

    @Test
    void 이미_있던_후보는_삽입되지_않았다고_답한다() {
        when(nativeQuery.executeUpdate()).thenReturn(0);

        assertThat(repository.insertIfAbsent(candidate())).isFalse();
    }

    @Test
    void 삽입_파라미터_수가_자리표시자_수와_같다() {
        // 자리표시자 하나가 어긋나면 컬럼이 통째로 밀린다. 컴파일러가 잡아 주지 않는 자리다.
        when(nativeQuery.executeUpdate()).thenReturn(1);

        repository.insertIfAbsent(candidate());

        org.mockito.Mockito.verify(entityManager).createNativeQuery(sql.capture());
        long placeholders = sql.getValue().chars().filter(ch -> ch == '?').count();
        org.mockito.Mockito.verify(nativeQuery, org.mockito.Mockito.times((int) placeholders))
                .setParameter(anyInt(), any());
    }

    @Test
    void 계획_대상_조회는_술어를_리터럴로_적는다() {
        // 바인드 파라미터로 넣으면 플래너가 일반 계획에서 술어를 증명하지 못해
        // ix_cand_wave (wave_id, status) 의 뒤 컬럼을 못 쓴다 (CLAUDE.md 코딩 컨벤션).
        repository.findPlannableInWave(Ids.newId());

        org.mockito.Mockito.verify(entityManager).createQuery(sql.capture(),
                eq(DispatchCandidateEntity.class));
        assertThat(sql.getValue())
                .contains("CandidateStatus.PENDING")
                .doesNotContain(":status");
    }

    @Test
    void 없는_후보는_빈_값이다() {
        when(entityManager.find(eq(DispatchCandidateEntity.class), any())).thenReturn(null);

        assertThat(repository.findById(Ids.newId())).isEmpty();
    }

    @Test
    void 찾은_후보를_도메인으로_돌려준다() {
        DispatchCandidate candidate = candidate();
        when(entityManager.find(eq(DispatchCandidateEntity.class), any()))
                .thenReturn(DispatchCandidateEntity.from(candidate));

        assertThat(repository.findById(candidate.orderId()))
                .hasValueSatisfying(found -> assertThat(found.orderId())
                        .isEqualTo(candidate.orderId()));
    }

    @Test
    void 갱신은_영속_엔티티에_반영된다() {
        DispatchCandidate candidate = candidate();
        DispatchCandidateEntity entity = DispatchCandidateEntity.from(candidate);
        when(entityManager.find(DispatchCandidateEntity.class, candidate.orderId()))
                .thenReturn(entity);
        candidate.recordPlanResult(CandidateStatus.PLANNED, NOW.plusSeconds(60));

        repository.update(candidate);

        assertThat(entity.toDomain().status()).isEqualTo(CandidateStatus.PLANNED);
    }

    @Test
    void 없는_후보를_갱신하면_실패한다() {
        // 조용히 넘어가면 계획 결과가 어디에도 남지 않은 채 커밋된다.
        DispatchCandidate candidate = candidate();
        when(entityManager.find(eq(DispatchCandidateEntity.class), any())).thenReturn(null);

        assertThatThrownBy(() -> repository.update(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없는 후보를 갱신할 수 없습니다");
    }

    @Test
    void 널_후보는_거부한다() {
        assertThatThrownBy(() -> repository.insertIfAbsent(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 엔티티매니저는_필수다() {
        assertThatThrownBy(() -> new JpaDispatchCandidateRepository(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityManager");
    }

    @Test
    void 계획_대상이_없으면_빈_목록이다() {
        UUID waveId = Ids.newId();

        assertThat(repository.findPlannableInWave(waveId)).isEmpty();
    }
}
