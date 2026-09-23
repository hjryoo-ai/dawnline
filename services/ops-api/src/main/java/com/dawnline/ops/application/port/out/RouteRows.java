package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.RouteStatus;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code rm_routes} (DESIGN.md §5.5). 계약은 {@link OrderRows} 와 같다.
 */
public interface RouteRows {

    /**
     * 없는 행을 키만으로 만들고 전부를 {@code route_id} 순서로 잠근다.
     *
     * @param routeIds 라우트들 (중복 허용)
     * @return 라우트 → 판정용 현재 값
     */
    Map<UUID, RouteRow> lock(Collection<UUID> routeIds);

    /**
     * @param routeId 라우트
     * @param patch   적을 칸
     */
    void write(UUID routeId, Patch<RouteColumn> patch);

    /**
     * {@code completed_count}·{@code failed_count} 를 {@code rm_orders} 에서 다시 센다(ADR-051 결정 4).
     *
     * <p><strong>계획이 도착한 라우트만</strong> 센다({@code revision} 이 있는 행). 소속을 모르는
     * 동안의 0 은 「배송한 것이 없다」가 아니라 「아직 모른다」이고, 그것을 0 으로 적으면 부재가
     * 값이 된다. {@link #lock} 으로 잠근 행에만 부른다.
     *
     * @param routeIds 라우트들
     */
    void recount(Collection<UUID> routeIds);

    /**
     * @param revision 라우트 칸을 쓴 개정
     * @param status   라우트 상태
     */
    record RouteRow(@Nullable Integer revision, @Nullable RouteStatus status) {
    }
}
