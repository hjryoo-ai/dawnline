package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.util.Objects;

/**
 * 계획 대상 주문 하나 (DESIGN.md §6.2). {@code dispatch_candidates} 한 행에 대응한다.
 *
 * <p>후보는 §6.5 1단계에서 {@link Stop} 으로 통합되고, 그 뒤의 모든 판정은 stop 단위다.
 * 통합 조건은 <strong>같은 geohash7 + 같은 약속창</strong>이다.
 *
 * <p>권역(zone)은 따로 담지 않는다 — {@code point.geohash5()} 가 곧 권역이기 때문이다(ADR-021 이
 * 권역을 geohash5 셀로 정의했다). 같은 사실을 두 필드로 들고 있으면 갈라진다.
 *
 * @param id            주문 id
 * @param point         배송지
 * @param parcel        화물
 * @param promised      약속 배송창 (§2.2)
 * @param serviceSeconds 이 주문의 하차·전달 시간(초)
 * @param priority      우선도. 0 이 기본이고 클수록 우선 (§6.3 {@code PRIORITY_BOOST})
 */
public record Candidate(OrderId id, GeoPoint point, Parcel parcel, TimeWindow promised,
        int serviceSeconds, int priority) {

    public Candidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(parcel, "parcel");
        Objects.requireNonNull(promised, "promised");
        if (serviceSeconds < 0) {
            throw ValidationException.field("serviceSeconds", serviceSeconds, "서비스 시간은 음수일 수 없습니다");
        }
        if (priority < 0) {
            throw ValidationException.field("priority", priority, "우선도는 음수일 수 없습니다");
        }
    }

    /** 이 후보가 속한 권역 (geohash5, ADR-021). */
    public String zone() {
        return point.geohash5();
    }

    /** 통합 키 — 같은 값끼리 한 {@link Stop} 이 된다 (§6.5 1단계). */
    public MergeKey mergeKey() {
        return new MergeKey(point.geohash7(), promised);
    }

    /**
     * {@link Stop} 통합 키.
     *
     * @param geohash7 약 153 m 격자
     * @param promised 약속창
     */
    public record MergeKey(String geohash7, TimeWindow promised) {
    }
}
