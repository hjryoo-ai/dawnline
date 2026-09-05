package com.dawnline.dispatch.application.port.in;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code fulfillment.planned} 에서 계획에 필요한 것만 뽑은 스냅샷.
 *
 * <p>주소 문자열도 고객 id 도 담지 않는다 — 최적화가 쓰는 것은 좌표와 화물과 창뿐이고,
 * 담지 않으면 로그에 샐 수도 없다(CLAUDE.md — 전체 주소·고객 식별 정보 로그 금지).
 *
 * <p>시각을 <strong>마이크로초로 자른다</strong>. PostgreSQL {@code TIMESTAMPTZ} 가 마이크로초라
 * 자르지 않으면 저장 전후의 값이 달라진다 — Phase 2 에서 그것 때문에 웨이브 자연키 조회가
 * 어긋난 적이 있다.
 *
 * @param orderId        주문 id
 * @param waveId         소속 웨이브
 * @param campId         캠프
 * @param zoneId         권역. 지오코딩이 실패했으면 {@code null}
 * @param location       배송지 좌표
 * @param weightG        중량(g)
 * @param volumeCm3      부피(㎤)
 * @param requiresCold   냉장 필요
 * @param hazmat         위험물
 * @param promised       약속 배송창
 * @param serviceSeconds 하차·전달 시간(초)
 * @param priority       우선도
 */
public record PlannedOrderSnapshot(UUID orderId, UUID waveId, UUID campId, @Nullable UUID zoneId,
        GeoPoint location, int weightG, int volumeCm3, boolean requiresCold, boolean hazmat,
        TimeWindow promised, int serviceSeconds, int priority) {

    public PlannedOrderSnapshot {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(waveId, "waveId");
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(promised, "promised");
        promised = new TimeWindow(promised.start().truncatedTo(ChronoUnit.MICROS),
                promised.end().truncatedTo(ChronoUnit.MICROS));
        if (weightG < 0 || volumeCm3 < 0) {
            throw ValidationException.field("parcel", weightG + "/" + volumeCm3,
                    "중량·부피는 음수일 수 없습니다");
        }
    }
}
