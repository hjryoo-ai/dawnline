package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.RouteStatus;
import java.time.Instant;
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
     * @param routeIds  라우트들 (중복 허용)
     * @param touchedAt 새 행의 {@code updated_at} — 보존의 나이 (ADR-058)
     * @return 라우트 → 판정용 현재 값
     */
    Map<UUID, RouteRow> lock(Collection<UUID> routeIds, Instant touchedAt);

    /**
     * @param routeId   라우트
     * @param patch     적을 칸 — 비어 있으면 아무것도 하지 않는다
     * @param touchedAt {@code updated_at} — 사실이 아니라 프로젝션의 기록이다
     */
    void write(UUID routeId, Patch<RouteColumn> patch, Instant touchedAt);

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
