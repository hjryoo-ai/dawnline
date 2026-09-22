package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase.DeliveryStatusCommand;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RouteProgress;
import com.dawnline.dispatch.application.port.out.RouteProgressCache;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.RouteStopStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code route:{id}:progress} 는 Redis 가 없어도 성립한다 — <strong>불변규칙 7</strong> (§7.2).
 *
 * <p>§7.2 의 폴백 칸이 「DB 조회」다. 이 클래스는 그 문장이 코드에 있는지를 본다: Redis 를 쓸 수
 * 없는 상태에서 ① {@code delivery.status} 전이가 그대로 일어나고 ② 같은 세 칸이
 * {@code route_stops} 에서 다시 만들어지는가.
 *
 * <p><strong>전제를 첫 어설션으로 스스로 말한다.</strong> 폴백 테스트는 의존성이 실제로 불가할
 * 때만 무언가를 증명한다 — 전제가 조용히 무너지면 테스트는 계속 통과하면서 아무것도 검사하지
 * 않는다. 이 저장소에서 세 번 있었던 일이라 규칙이 되었다(CLAUDE.md).
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RouteProgressFallbackIT — Redis 가 없어도 진행은 DB 에서 나온다")
class RouteProgressFallbackIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부). */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private RecordDeliveryStatusUseCase recordStatus;

    @Autowired
    private RouteMutations routes;

    @Autowired
    private RouteProgressCache cache;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 죽은 Redis 를 가리킨다.
     *
     * <p>{@code host}/{@code port} 가 아니라 {@code url} 로 덮는다 — {@code url} 이 우선하므로
     * {@code @DynamicPropertySource} 들의 적용 순서와 무관하게 이긴다({@code GeoFallbackIT} 의
     * 교훈).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:1");
    }

    /** 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다 (ADR-027 후속 정정). */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @BeforeEach
    void 전제_Redis_를_쓸_수_없다() {
        assertThatThrownBy(() -> redis.opsForValue().get("전제-확인"))
                .as("이 테스트의 전제: Redis 를 쓸 수 없다")
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
        });
    }

    @Test
    void 캐시_어댑터는_예외를_밖으로_내지_않는다() {
        // 여기서 DataAccessException 이 올라오면 캐시 하나가 delivery.status 소비 전체를 멈춘다 —
        // 진실 저장소가 아닌 것이 진실 저장소처럼 실패하는 모양이다.
        UUID routeId = Ids.newId();

        cache.put(routeId, new RouteProgress(2, 1, 0));

        assertThat(cache.get(routeId)).as("죽은 Redis 는 «없음» 으로 답한다").isEmpty();
    }

    @Test
    void Redis_가_없어도_전이는_일어난다() {
        Planned planned = plannedRoute();
        RouteView.StopView first = planned.stops().getFirst();

        tx().executeWithoutResult(status -> recordStatus.record(new DeliveryStatusCommand(
                planned.routeId(), first.seq(), first.orderIds(), RouteStopStatus.COMPLETED,
                PlanningClock.PLAN_AT)));

        assertThat(statusOf(planned.routeId(), first.seq())).isEqualTo("COMPLETED");
    }

    @Test
    void 진행_세_칸이_route_stops_에서_다시_만들어진다() {
        Planned planned = plannedRoute();
        RouteView.StopView first = planned.stops().getFirst();
        RouteView.StopView second = planned.stops().get(1);

        tx().executeWithoutResult(status -> {
            recordStatus.record(new DeliveryStatusCommand(planned.routeId(), first.seq(),
                    first.orderIds(), RouteStopStatus.COMPLETED, PlanningClock.PLAN_AT));
            recordStatus.record(new DeliveryStatusCommand(planned.routeId(), second.seq(),
                    second.orderIds(), RouteStopStatus.FAILED, PlanningClock.PLAN_AT));
        });

        RouteProgress progress =
                tx().execute(status -> routes.progressOf(planned.routeId())).orElseThrow();
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(progress.nextSeq())
                .as("종결되지 않은 가장 작은 seq — 캐시가 죽어도 이 값이 진실이다")
                .isEqualTo(planned.stops().get(2).seq());
    }

    @Test
    void 취소된_stop_은_다음_순번에_들어가지_않는다() {
        // 기사가 가지 않을 지점이다. 세면 「다음에 갈 곳」이 영영 그 자리에 멈춘다.
        Planned planned = plannedRoute();
        int firstSeq = planned.stops().getFirst().seq();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                        "UPDATE route_stops SET status = 'CANCELLED' WHERE route_id = ? AND seq = ?")
                .setParameter(1, planned.routeId()).setParameter(2, firstSeq).executeUpdate());

        RouteProgress progress =
                tx().execute(status -> routes.progressOf(planned.routeId())).orElseThrow();

        assertThat(progress.nextSeq()).isEqualTo(planned.stops().get(1).seq());
    }

    @Test
    void 모든_stop_이_끝나면_다음_순번이_없다() {
        Planned planned = plannedRoute();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                        "UPDATE route_stops SET status = 'COMPLETED' WHERE route_id = ?")
                .setParameter(1, planned.routeId()).executeUpdate());

        RouteProgress progress =
                tx().execute(status -> routes.progressOf(planned.routeId())).orElseThrow();

        assertThat(progress.done()).isTrue();
        assertThat(progress.completed()).isEqualTo(planned.stops().size());
    }

    // --- 픽스처 --------------------------------------------------------------

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String statusOf(UUID routeId, int seq) {
        return tx().execute(status -> (String) entityManager.createNativeQuery(
                        "SELECT status FROM route_stops WHERE route_id = ? AND seq = ?")
                .setParameter(1, routeId).setParameter(2, seq).getSingleResult());
    }

    private record Planned(UUID routeId, List<RouteView.StopView> stops) {
    }

    private Planned plannedRoute() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 8);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        UUID routeId = plan.routes().getFirst().routeId();
        RouteView route = tx().execute(status -> planQueries.findRoute(routeId)).orElseThrow();
        assertThat(route.stops()).as("이 테스트는 stop 셋 이상을 전제한다").hasSizeGreaterThan(2);
        return new Planned(routeId, route.stops());
    }

    /** 약속창의 기준을 {@link PlanningClock#PLAN_AT} 에서 잡는다 — 벽시계가 아니다. */
    private List<UUID> seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(5)));
        List<UUID> orderIds = new ArrayList<>(count);
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                orderIds.add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i % 8 + 1),
                                CAMP.lng() + 0.005d * (i / 8 + 1)),
                        40_000, 80_000, false, false, window, 60, false, 0, now));
            }
        });
        return orderIds;
    }
}
