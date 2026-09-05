package com.dawnline.dispatch.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link JpaRoutePlanRepository} 가 만드는 SQL·JPQL.
 *
 * <p>실행 결과는 {@code PlanExecutionIT} 가 실물 PostgreSQL 로 본다. 여기서 보는 것은 §5.3 의
 * 멱등이 걸려 있는 자리 — {@code ON CONFLICT (wave_id)} — 와, depot 스냅샷이 삽입 파라미터에
 * 실리는가다. depot 이 빠지면 이벤트 없는 재실행(§6.8)이 출발 지점을 모른다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JpaRoutePlanRepositoryTest {

    private static final GeoPoint DEPOT = GeoPoint.of(37.5665, 126.9780);

    private EntityManager entityManager;
    private JpaRoutePlanRepository repository;
    private Query nativeQuery;
    private TypedQuery<RoutePlanEntity> typed;
    private ArgumentCaptor<String> sql;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        repository = new JpaRoutePlanRepository(entityManager);
        sql = ArgumentCaptor.forClass(String.class);

        nativeQuery = mock(Query.class);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);

        typed = mock(TypedQuery.class);
        when(typed.setParameter(anyString(), any())).thenReturn(typed);
        when(typed.setMaxResults(anyInt())).thenReturn(typed);
        when(typed.getResultStream()).thenReturn(java.util.stream.Stream.empty());
        when(typed.getResultList()).thenReturn(List.of());
        when(entityManager.createQuery(anyString(), eq(RoutePlanEntity.class))).thenReturn(typed);
    }

    private static RoutePlan requested() {
        return RoutePlan.request(Ids.newId(), Ids.newId(), Ids.newId(), DEPOT);
    }

    @Test
    void 삽입은_웨이브당_하나로_멱등하다() {
        when(nativeQuery.executeUpdate()).thenReturn(1);

        assertThat(repository.insertIfAbsent(requested())).isTrue();

        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO route_plans")
                .contains("ON CONFLICT (wave_id) DO NOTHING");
    }

    @Test
    void 같은_웨이브의_두_번째_요청은_삽입되지_않았다고_답한다() {
        // 두 소비자가 같은 wave.closed 를 받아도 계획은 하나여야 한다 (§5.3).
        when(nativeQuery.executeUpdate()).thenReturn(0);

        assertThat(repository.insertIfAbsent(requested())).isFalse();
    }

    @Test
    void depot_스냅샷이_삽입_파라미터에_실린다() {
        when(nativeQuery.executeUpdate()).thenReturn(1);

        repository.insertIfAbsent(requested());

        verify(nativeQuery).setParameter(5, DEPOT.lat());
        verify(nativeQuery).setParameter(6, DEPOT.lng());
    }

    @Test
    void depot_없이_되살아난_계획도_삽입할_수_있다() {
        // V2 컬럼은 nullable 이다 — 그 컬럼이 생기기 전에 만들어진 행이 있기 때문이다.
        RoutePlan legacy = RoutePlan.rehydrate(Ids.newId(), Ids.newId(), Ids.newId(),
                PlanStatus.REQUESTED, null, null, null, null, null, null, null, null, null, null,
                null, null, 0L);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        assertThat(repository.insertIfAbsent(legacy)).isTrue();

        verify(nativeQuery).setParameter(5, (Object) null);
        verify(nativeQuery).setParameter(6, (Object) null);
    }

    @Test
    void 회수_대상_조회는_술어를_리터럴로_적는다() {
        // PLANNING 을 바인드로 넣으면 부분 인덱스를 못 탄다 (CLAUDE.md 코딩 컨벤션).
        repository.findStalePlanning(Instant.parse("2026-09-06T01:00:00Z"), 50);

        verify(entityManager).createQuery(sql.capture(), eq(RoutePlanEntity.class));
        assertThat(sql.getValue())
                .contains("PlanStatus.PLANNING")
                .doesNotContain(":status");
        verify(typed).setMaxResults(50);
    }

    @Test
    void 회수_대상이_없으면_빈_목록이다() {
        assertThat(repository.findStalePlanning(Instant.EPOCH, 10)).isEmpty();
    }

    @Test
    void 웨이브로_찾지_못하면_빈_값이다() {
        assertThat(repository.findByWaveId(Ids.newId())).isEmpty();
    }

    @Test
    void 식별자로_찾지_못하면_빈_값이다() {
        when(entityManager.find(eq(RoutePlanEntity.class), any())).thenReturn(null);

        assertThat(repository.findById(Ids.newId())).isEmpty();
    }

    @Test
    void 없는_계획을_갱신하면_실패한다() {
        // 조용히 넘어가면 계획 결과가 어디에도 남지 않은 채 커밋된다.
        RoutePlan plan = requested();
        when(entityManager.find(eq(RoutePlanEntity.class), any())).thenReturn(null);

        assertThatThrownBy(() -> repository.update(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없는 계획을 갱신할 수 없습니다");
    }

    @Test
    void 널_계획은_거부한다() {
        assertThatThrownBy(() -> repository.insertIfAbsent(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 엔티티매니저는_필수다() {
        assertThatThrownBy(() -> new JpaRoutePlanRepository(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityManager");
    }

    @Test
    void 상태는_이름_문자열로_넘어간다() {
        // 네이티브 SQL 이라 enum 을 그대로 넘기면 드라이버가 바이트 배열로 보낸다.
        UUID waveId = Ids.newId();
        RoutePlan plan = RoutePlan.request(Ids.newId(), waveId, Ids.newId(), DEPOT);
        when(nativeQuery.executeUpdate()).thenReturn(1);

        repository.insertIfAbsent(plan);

        verify(nativeQuery).setParameter(2, waveId);
        verify(nativeQuery).setParameter(4, "REQUESTED");
    }
}
