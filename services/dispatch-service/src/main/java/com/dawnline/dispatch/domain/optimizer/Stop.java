package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.util.List;
import java.util.Objects;

/**
 * 통합된 방문 지점 (DESIGN.md §6.2, §6.5 1단계).
 *
 * <h2>왜 판정 단위가 후보가 아니라 stop 인가</h2>
 * §6.5 의 1단계가 통합이라 그 뒤로는 주문 하나가 단독으로 배정되는 일이 없다. 용량·냉장·우선도는
 * 전부 <strong>합쳐진 값</strong>으로 봐야 하고({@code parcel} 은 합, 냉장·위험물은 OR,
 * {@code priority} 는 최댓값), 배정에 실패하면 이 stop 의 주문이 <em>함께</em> 미배정이 된다.
 * 그래서 룰의 서명도 {@code Stop} 을 받는다(§6.3).
 *
 * @param point          방문 좌표. 통합된 주문들의 대표점이다
 * @param orderIds       이 지점에서 배송할 주문들. 최소 1건
 * @param parcel         합쳐진 화물
 * @param promised       약속창. 통합 조건이 "같은 약속창" 이므로 하나로 정해진다
 * @param serviceSeconds 하차·전달 시간(초). 통합된 주문 수에 따라 커진다
 * @param priority       통합된 주문들의 최대 우선도
 */
public record Stop(GeoPoint point, List<OrderId> orderIds, Parcel parcel, TimeWindow promised,
        int serviceSeconds, int priority) {

    public Stop {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(parcel, "parcel");
        Objects.requireNonNull(promised, "promised");
        orderIds = List.copyOf(Objects.requireNonNull(orderIds, "orderIds"));
        if (orderIds.isEmpty()) {
            throw new ValidationException("stop 에는 주문이 최소 하나 있어야 합니다", java.util.Map.of());
        }
        if (serviceSeconds < 0) {
            throw ValidationException.field("serviceSeconds", serviceSeconds, "서비스 시간은 음수일 수 없습니다");
        }
        if (priority < 0) {
            throw ValidationException.field("priority", priority, "우선도는 음수일 수 없습니다");
        }
    }

    /** 이 stop 이 속한 권역 (geohash5, ADR-021). {@code ZONE_AFFINITY} 소프트 룰이 본다. */
    public String zone() {
        return point.geohash5();
    }

    /** 통합된 주문 수. */
    public int orderCount() {
        return orderIds.size();
    }
}
