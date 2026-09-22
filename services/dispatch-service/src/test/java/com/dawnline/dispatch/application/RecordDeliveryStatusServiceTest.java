package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase.DeliveryStatusCommand;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RouteProgress;
import com.dawnline.dispatch.application.port.out.RouteProgressCache;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code delivery.status} → {@code route_stops.status} (ADR-047).
 *
 * <p>전이 규칙 자체는 {@code RouteStopTransitionTest} 가 본다. 여기서 보는 것은 <em>그 판정
 * 바깥</em>이다 — 어떤 stop 을 찾는가, 무엇을 세는가, 진행 캐시를 언제 쓰는가.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RecordDeliveryStatusService — 주문으로 찾고, 개정으로 거르지 않는다")
class RecordDeliveryStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final Instant SCANNED_AT = NOW.plus(Duration.ofMinutes(20));
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(NOW, NOW.plus(Duration.ofHours(8)));
    private static final GeoPoint NEAR = GeoPoint.of(37.5700, 126.9779);
    private static final GeoPoint MID = GeoPoint.of(37.5800, 126.9779);
    private static final GeoPoint FAR = GeoPoint.of(37.5900, 126.9779);

    private final InMemoryDispatchPorts.Candidates candidates =
            new InMemoryDispatchPorts.Candidates();
    private final InMemoryDispatchPorts.CancellableRoutes routes =
            new InMemoryDispatchPorts.CancellableRoutes(candidates);
    private final RecordingCache cache = new RecordingCache();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DispatchMetrics metrics = new DispatchMetrics(registry);

    private final RecordDeliveryStatusService service =
            new RecordDeliveryStatusService(routes, cache, metrics);

    /** 캐시에 무엇이 언제 쓰였는지 본다. */
    private static final class RecordingCache implements RouteProgressCache {

        private final Map<UUID, RouteProgress> written = new LinkedHashMap<>();

        @Override
        public void put(UUID routeId, RouteProgress progress) {
            written.put(routeId, progress);
        }

        @Override
        public Optional<RouteProgress> get(UUID routeId) {
            return Optional.ofNullable(written.get(routeId));
        }
    }

    /** 상태 쓰기에서 실패하는 라우트 — 카운터가 쓰기 <em>뒤</em>인지 보기 위한 것이다. */
    private final class FailingWrite implements RouteMutations {

        @Override
        public Optional<AssignedStop> findAssignedStop(UUID orderId) {
            return routes.findAssignedStop(orderId);
        }

        @Override
        public void markStopStatus(UUID stopId, RouteStopStatus status) {
            throw new IllegalStateException("적재 실패");
        }

        @Override
        public Optional<RouteProgress> progressOf(UUID routeId) {
            return routes.progressOf(routeId);
        }

        @Override
        public Optional<RouteHeader> findHeader(UUID routeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.dawnline.dispatch.domain.optimizer.Stop> loadStops(UUID routeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> findStopOf(UUID routeId, UUID orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rewrite(UUID routeId,
                com.dawnline.dispatch.domain.optimizer.PlannedRoute route) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear(UUID routeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int bumpRevision(UUID routeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancelStopIfAllOrdersCancelled(UUID stopId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retime(UUID routeId,
                com.dawnline.dispatch.domain.optimizer.PlannedRoute route) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.dawnline.dispatch.application.port.out.RouteSnapshot> snapshot(
                UUID routeId) {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------------------------------------------------ 전이

    @Test
    void 도착_스캔이_stop_을_ARRIVED_로_옮긴다() {
        Route route = route();

        service.record(command(route.routeId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.ARRIVED));

        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.ARRIVED);
    }

    @Test
    void 완료_스캔은_도착을_건너뛰어도_적용된다() {
        Route route = route();

        service.record(command(route.routeId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.COMPLETED);
    }

    // ------------------------------------------------------------ 결정 1 — 주문으로 찾는다

    @Test
    void 페이로드의_순번이_아니라_주문으로_찾는다() {
        // 개정이 순서를 바꿔 seq 가 다른 지점을 가리키는 상황. 페이로드는 1 이라고 말하지만
        // 이 주문의 stop 은 3 번이다 — 옮겨야 하는 것은 3 번이다 (ADR-047 결정 1).
        Route route = route();

        service.record(command(route.routeId(), 1, route.mergedOrderIds(),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 3).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.PLANNED);
    }

    @Test
    void 이_라우트에_없는_주문의_상태는_철_지난_것으로_세고_버린다() {
        Route route = route();

        service.record(command(Ids.newId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.PLANNED);
        assertThat(staleCount()).isEqualTo(1.0d);
    }

    @Test
    void 한_stop_의_주문이_갈라져도_남아_있는_쪽으로_찾는다() {
        // 운영자 재배정은 stop 이 아니라 주문 하나를 옮긴다. 첫 주문만 보고 판정하면 「옮겨 간
        // 쪽」을 집었을 때 남아 있는 방문을 놓친다.
        Route route = route();
        List<UUID> split = new ArrayList<>();
        split.add(Ids.newId());                 // 이 라우트에 없는 주문이 먼저 온다
        split.add(route.middleOrderId());

        service.record(command(route.routeId(), 2, split, RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 2).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(staleCount()).isZero();
    }

    // ------------------------------------------------------------ 결정 2 — 개정으로 거르지 않는다

    @Test
    void 개정이_오른_뒤에_도착한_완료도_적용한다() {
        // route.assigned 였다면 낮은 revision 은 버려진다(ADR-045). 이 이벤트는 사실이라
        // 버리지 않는다 — 버리면 §6.8 의 「미완료 stop 만」이 읽을 값이 사라진다.
        Route route = route();
        routes.bumpRevision(route.routeId());
        routes.bumpRevision(route.routeId());

        service.record(command(route.routeId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(staleCount()).isZero();
    }

    // ------------------------------------------------------------ 결정 3 — 취소를 먼저 묻는다

    @Test
    void 취소된_stop_에_도착한_완료는_무시하고_따로_센다() {
        Route route = route();
        routes.row(route.routeId(), 2).status = RouteStopStatus.CANCELLED;

        service.record(command(route.routeId(), 2, List.of(route.middleOrderId()),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 2).status).isEqualTo(RouteStopStatus.CANCELLED);
        assertThat(registry.counter(DispatchMetrics.SCAN_AFTER_CANCEL).count()).isEqualTo(1.0d);
        assertThat(staleCount()).as("취소는 철 지난 것과 다른 사건이다 — 섞으면 창을 못 잰다")
                .isZero();
    }

    @Test
    void 역행_스캔은_철_지난_것으로_센다() {
        Route route = route();
        routes.row(route.routeId(), 1).status = RouteStopStatus.COMPLETED;

        service.record(command(route.routeId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.ARRIVED));

        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(staleCount()).isEqualTo(1.0d);
    }

    // ------------------------------------------------------------ §7.2 진행

    @Test
    void 적용한_뒤에_진행을_다시_쓴다() {
        Route route = route();

        service.record(command(route.routeId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.COMPLETED));

        RouteProgress progress = cache.get(route.routeId()).orElseThrow();
        assertThat(progress.nextSeq()).as("종결되지 않은 가장 작은 seq").isEqualTo(2);
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.failed()).isZero();
    }

    @Test
    void 마지막_stop_이_끝나면_다음_순번이_없다() {
        Route route = route();
        for (int seq = 1; seq <= 3; seq++) {
            routes.row(route.routeId(), seq).status = RouteStopStatus.COMPLETED;
        }
        routes.row(route.routeId(), 3).status = RouteStopStatus.ARRIVED;

        service.record(command(route.routeId(), 3, route.mergedOrderIds(),
                RouteStopStatus.FAILED));

        RouteProgress progress = cache.get(route.routeId()).orElseThrow();
        assertThat(progress.done()).isTrue();
        assertThat(progress.failed()).isEqualTo(1);
    }

    @Test
    void 적용하지_않은_이벤트는_진행을_건드리지_않는다() {
        Route route = route();
        routes.row(route.routeId(), 1).status = RouteStopStatus.COMPLETED;

        service.record(command(route.routeId(), 1, List.of(route.firstOrderId()),
                RouteStopStatus.ARRIVED));

        assertThat(cache.get(route.routeId())).isEmpty();
    }

    // ------------------------------------------------------------ 세는 순서

    @Test
    void 적재가_실패하면_카운터를_올리지_않는다() {
        // 미터 레지스트리는 트랜잭션을 모른다. 먼저 올리면 롤백된 작업의 숫자가 남고, 그 차이는
        // 장애 때 가장 커진다 — 지표가 가장 많이 읽히는 순간에 가장 많이 틀린다.
        Route route = route();
        RecordDeliveryStatusService failing =
                new RecordDeliveryStatusService(new FailingWrite(), cache, metrics);

        assertThatThrownBy(() -> failing.record(command(route.routeId(), 1,
                List.of(route.firstOrderId()), RouteStopStatus.COMPLETED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(staleCount()).isZero();
        assertThat(registry.counter(DispatchMetrics.SCAN_AFTER_CANCEL).count()).isZero();
        assertThat(cache.get(route.routeId())).isEmpty();
    }

    // ------------------------------------------------------------ 픽스처

    private double staleCount() {
        return registry.counter(MessagingMetrics.EVENT_STALE,
                MessagingMetrics.TAG_CONSUMER, DispatchMetrics.DELIVERY_STATUS_CONSUMER,
                MessagingMetrics.TAG_EVENT_TYPE, DispatchMetrics.DELIVERY_STATUS_EVENT_TYPE)
                .count();
    }

    private static DeliveryStatusCommand command(UUID routeId, int stopSeq, List<UUID> orderIds,
            RouteStopStatus status) {
        return new DeliveryStatusCommand(routeId, stopSeq, orderIds, status, SCANNED_AT);
    }

    /** 세 stop 짜리 라우트. 3번은 주문 둘이 합쳐진 stop 이다 (§6.5 1단계). */
    private record Route(UUID routeId, UUID firstOrderId, UUID middleOrderId,
            List<UUID> mergedOrderIds) {
    }

    private Route route() {
        UUID first = candidate();
        UUID middle = candidate();
        List<UUID> merged = List.of(candidate(), candidate());

        List<InMemoryDispatchPorts.CancellableRoutes.StopRow> stops = new ArrayList<>();
        stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(
                1, NEAR, 60, NOW.plus(Duration.ofMinutes(10)), List.of(first), WINDOW));
        stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(
                2, MID, 60, NOW.plus(Duration.ofMinutes(30)), List.of(middle), WINDOW));
        stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(
                3, FAR, 120, NOW.plus(Duration.ofMinutes(50)), merged, WINDOW));
        return new Route(routes.route(Ids.newId(), Ids.newId(), stops), first, middle, merged);
    }

    private UUID candidate() {
        UUID orderId = Ids.newId();
        candidates.put(DispatchCandidate.load(orderId, Ids.newId(), CAMP_ID, null, NEAR,
                10_000, 20_000, false, false, WINDOW, 60, false, 0, NOW));
        return orderId;
    }
}
