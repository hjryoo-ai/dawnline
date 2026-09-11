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
        return new MergeKey(point.geohash7(), promised, parcel.requiresCold(), parcel.hazmat());
    }

    /**
     * {@link Stop} 통합 키.
     *
     * <h2>왜 제약이 키에 들어가는가</h2>
     * 통합은 "한 번에 배송할 수 있는 주문" 을 묶는 일이다. 좌표와 창이 같아도
     * <strong>함께 탈 수 없는</strong> 주문이 있다 — 위험물 한 건이 섞이면 그 stop 전체가
     * 위험물 차량만 탈 수 있게 되고, 옆의 평범한 아홉 건이 함께 그 차를 기다린다.
     * <strong>희소한 능력 하나가 인질을 잡는다.</strong>
     *
     * <p>운영에서도 같은 일이 일어난다 — 냉장 ∧ 위험물 차량이 한 대 고장 난 날의 캠프가 정확히
     * 이 상태다. 그래서 이것은 벤치마크의 문제가 아니라 <strong>모델의 문제</strong>이고,
     * 고칠 곳은 데이터셋이 아니라 여기다 ([ADR-033]).
     *
     * <p>대가는 stop 수 증가다. 같은 좌표이므로 <em>이동 거리는 늘지 않고</em>, 하차·전달 시간은
     * 원래 주문마다 더하므로(§6.5 1단계) 총 서비스 시간도 그대로다. 실제로 늘어나는 것은
     * "그 주소를 두 번 취급한다" 는 사실이고, 그건 {@code MAX_STOPS_PER_ROUTE} 소비로 모델에
     * 이미 반영돼 있다.
     *
     * @param geohash7     약 153 m 격자
     * @param promised     약속창
     * @param requiresCold 냉장 필요 — 같은 자리라도 냉장과 상온은 다른 stop 이다
     * @param hazmat       위험물
     */
    public record MergeKey(String geohash7, TimeWindow promised, boolean requiresCold,
            boolean hazmat) {
    }
}
