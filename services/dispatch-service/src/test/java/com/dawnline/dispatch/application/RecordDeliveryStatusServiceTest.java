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
@DisplayName("RecordDeliveryStatusService — 사실은 주문에 귀속된다")
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

    // ------------------------------------------------------------ 결정 1·2 — 사실은 주문의 것

    @Test
    void 페이로드의_순번이_아니라_주문으로_찾는다() {
        // 개정이 순서를 바꿔 seq 가 다른 지점을 가리키는 상황. 페이로드는 1 이라고 말하지만
        // 이 주문의 stop 은 3 번이다 — 옮겨야 하는 것은 3 번이다 (ADR-047 결정 1·2).
        Route route = route();

        service.record(command(route.routeId(), 1, route.mergedOrderIds(),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 3).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.PLANNED);
    }

    @Test
    void 어느_라우트에도_없는_주문의_상태만_철_지난_것으로_센다() {
        Route route = route();

        service.record(command(route.routeId(), 1, List.of(Ids.newId()),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 1).status).isEqualTo(RouteStopStatus.PLANNED);
        assertThat(staleCount()).isEqualTo(1.0d);
        assertThat(relocateCount()).isZero();
    }

    @Test
    void 이벤트가_말한_라우트에_없으면_지금_있는_라우트에_적용한다() {
        // 경합의 형태: A 의 기사가 배송했고, dispatch 는 그것을 모른 채 재계획으로 그 주문을
        // B 로 옮겼고, 그 뒤에 완료가 routeId=A 로 도착한다. 「A 에 없으니 버린다」면 dispatch 는
        // 그 주문이 B 에서 미완료라고 믿고 B 의 기사를 이미 배송된 곳으로 보낸다.
        Route moved = route();
        UUID 떠나온_라우트 = Ids.newId();

        service.record(command(떠나온_라우트, 1, List.of(moved.firstOrderId()),
                RouteStopStatus.COMPLETED));

        assertThat(routes.row(moved.routeId(), 1).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(relocateCount()).as("경합 창의 크기다 — stale 과 섞으면 잴 수 없다")
                .isEqualTo(1.0d);
        assertThat(staleCount()).isZero();
    }

    @Test
    void 재배치된_건의_진행은_지금_있는_라우트에_쓴다() {
        // 이벤트가 말한 라우트의 진행을 쓰면, 그 라우트에는 없는 stop 의 완료가 남의 진행을
        // 덮는다. 값이 틀리는 쪽은 언제나 이벤트 쪽이다.
        Route moved = route();
        UUID 떠나온_라우트 = Ids.newId();

        service.record(command(떠나온_라우트, 1, List.of(moved.firstOrderId()),
                RouteStopStatus.COMPLETED));

        assertThat(cache.get(떠나온_라우트)).isEmpty();
        assertThat(cache.get(moved.routeId()).orElseThrow().completed()).isEqualTo(1);
    }

    @Test
    void 한_stop_의_주문이_갈라지면_이벤트의_라우트에_있는_쪽을_택한다() {
        // 운영자 재배정은 stop 이 아니라 주문 하나를 옮긴다. 갈라졌으면 기사가 실제로 서 있던
        // 자리를 택한다 — 덜 표시하면 기사가 한 번 더 가고, 더 표시하면 배송되지 않은 주문이
        // 계획에서 사라진다.
        Route route = route();
        Route 옮겨간_곳 = route();
        List<UUID> split = new ArrayList<>();
        split.add(옮겨간_곳.firstOrderId());        // 다른 라우트로 간 주문이 먼저 온다
        split.add(route.middleOrderId());

        service.record(command(route.routeId(), 2, split, RouteStopStatus.COMPLETED));

        assertThat(routes.row(route.routeId(), 2).status).isEqualTo(RouteStopStatus.COMPLETED);
        assertThat(routes.row(옮겨간_곳.routeId(), 1).status).isEqualTo(RouteStopStatus.PLANNED);
        assertThat(staleCount()).isZero();
        assertThat(relocateCount()).isZero();
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
        assertThat(relocateCount()).isZero();
        assertThat(cache.get(route.routeId())).isEmpty();
    }

    @Test
    void 재배치를_적용하다_실패해도_카운터를_올리지_않는다() {
        // 같은 규칙이 새 카운터에도 걸린다 — 「경합 창이 넓어졌다」는 알림이 사실은 적재
        // 실패였다는 것을 대시보드는 말해 주지 않는다.
        Route moved = route();
        RecordDeliveryStatusService failing =
                new RecordDeliveryStatusService(new FailingWrite(), cache, metrics);

        assertThatThrownBy(() -> failing.record(command(Ids.newId(), 1,
                List.of(moved.firstOrderId()), RouteStopStatus.COMPLETED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(relocateCount()).isZero();
    }

    // ------------------------------------------------------------ 픽스처

    private double relocateCount() {
        return registry.counter(DispatchMetrics.STATUS_AFTER_RELOCATE).count();
    }

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
