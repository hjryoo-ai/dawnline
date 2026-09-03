package com.dawnline.order.domain;

import com.dawnline.common.error.ValidationException;

/**
 * 소포 제원 (DESIGN.md §4.3 {@code order.placed.v1}, §5.1 {@code orders} 테이블).
 *
 * <p>차량 적재 용량·냉장 여부 판단의 입력이 된다 (§6.3 룰 카탈로그). 그래서 값의 단위를
 * 타입 이름이 아니라 <strong>필드 이름</strong>에 박아 둔다 — {@code weightG}, {@code volumeCm3}.
 * kg 과 g 을 섞는 실수는 최적화 결과가 조용히 틀리는 방식으로만 드러난다.
 *
 * @param weightG      무게(g)
 * @param volumeCm3    부피(cm³)
 * @param requiresCold 냉장 필요 여부
 * @param hazmat       위험물 여부
 */
public record Parcel(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {

    /** 1톤 초과 소포는 이 도메인의 대상이 아니다. 오입력을 접수 단계에서 거른다. */
    private static final int MAX_WEIGHT_G = 1_000_000;

    /** 1m³. 위와 같은 이유의 상한이다. */
    private static final int MAX_VOLUME_CM3 = 1_000_000;

    public Parcel {
        if (weightG <= 0) {
            throw ValidationException.field("weightG", weightG, "0보다 커야 합니다");
        }
        if (weightG > MAX_WEIGHT_G) {
            throw ValidationException.field("weightG", weightG, MAX_WEIGHT_G + " 이하여야 합니다");
        }
        if (volumeCm3 <= 0) {
            throw ValidationException.field("volumeCm3", volumeCm3, "0보다 커야 합니다");
        }
        if (volumeCm3 > MAX_VOLUME_CM3) {
            throw ValidationException.field("volumeCm3", volumeCm3, MAX_VOLUME_CM3 + " 이하여야 합니다");
        }
    }

    /** 냉장·위험물처럼 차량 종류를 제한하는 속성이 있는가. */
    public boolean needsSpecialVehicle() {
        return requiresCold || hazmat;
    }
}
