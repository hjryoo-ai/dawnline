package com.dawnline.order.domain;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Geohash;
import com.dawnline.common.error.ValidationException;
import java.util.Objects;

/**
 * 배송지 (DESIGN.md §5.1 {@code orders} 테이블, §4.3 {@code order.placed.v1}).
 *
 * <p>{@code geohash7} 을 <strong>보관</strong>한다. 좌표에서 언제든 다시 계산할 수 있는 값이지만,
 * 이것이 §4.5 의 파티션 키이자 §6.2 의 stop 통합 키다. 저장해 두지 않으면 같은 주문의 파티션 키가
 * geohash 구현이 바뀌는 순간 달라져 이미 발행된 이벤트와 어긋난다.
 *
 * <p>{@code line}(전체 주소)은 도메인이 들고 있지만 <strong>로그·읽기 모델에는 나가지 않는다</strong>
 * (CLAUDE.md 로그 규칙, §10). {@link #toString()} 을 마스킹해 실수로 찍히는 경로를 막는다.
 *
 * @param line       도로명 주소 전체
 * @param postalCode 우편번호
 * @param point      좌표
 * @param geohash7   {@code point} 의 7자리 geohash (약 153m 격자)
 */
public record DeliveryAddress(String line, String postalCode, GeoPoint point, String geohash7) {

    private static final int MAX_LINE_LENGTH = 200;
    private static final int MAX_POSTAL_CODE_LENGTH = 10;

    public DeliveryAddress {
        Objects.requireNonNull(point, "point");
        line = requireText(line, "line", MAX_LINE_LENGTH);
        postalCode = requireText(postalCode, "postalCode", MAX_POSTAL_CODE_LENGTH);
        Objects.requireNonNull(geohash7, "geohash7");
        if (geohash7.length() != Geohash.STOP_PRECISION) {
            throw ValidationException.field("geohash7", geohash7,
                    Geohash.STOP_PRECISION + "자리여야 합니다");
        }
        // 저장된 geohash 가 좌표와 다르면 파티션 키와 실제 위치가 어긋난다.
        // 그 어긋남은 라우팅이 이상해진 뒤에야 드러나므로 만들 때 막는다.
        String expected = Geohash.encode(point, Geohash.STOP_PRECISION);
        if (!expected.equals(geohash7)) {
            throw ValidationException.field("geohash7", geohash7,
                    "좌표(" + point.lat() + ", " + point.lng() + ")의 geohash7 은 " + expected + " 입니다");
        }
    }

    /**
     * 좌표에서 geohash7 을 계산해 만든다. 일반적인 생성 경로다.
     *
     * @param line       도로명 주소 전체
     * @param postalCode 우편번호
     * @param point      좌표
     */
    public static DeliveryAddress of(String line, String postalCode, GeoPoint point) {
        Objects.requireNonNull(point, "point");
        return new DeliveryAddress(line, postalCode, point, Geohash.encode(point, Geohash.STOP_PRECISION));
    }

    /** 권역(zone) 매핑용 5자리 geohash (§5.2 FC 선택 규칙 4번). */
    public String geohash5() {
        return geohash7.substring(0, Geohash.ZONE_PRECISION);
    }

    /**
     * 전체 주소를 <strong>드러내지 않는</strong> 표현.
     *
     * <p>로그·예외 메시지에 애그리거트가 통째로 실리는 경로는 반드시 생긴다. 그때 주소가 그대로
     * 찍히지 않도록 여기서 막는다 (§9.3, §10). 디버깅에 필요한 위치 정보는 geohash7 이 준다.
     */
    @Override
    public String toString() {
        return "DeliveryAddress[postalCode=" + postalCode + ", geohash7=" + geohash7 + ", line=***]";
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            throw ValidationException.field(field, value, "비어 있을 수 없습니다");
        }
        if (trimmed.length() > maxLength) {
            throw ValidationException.field(field, trimmed.length(), maxLength + "자 이하여야 합니다");
        }
        return trimmed;
    }
}
