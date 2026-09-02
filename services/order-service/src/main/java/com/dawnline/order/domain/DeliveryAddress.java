package com.dawnline.order.domain;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Geohash;
import com.dawnline.common.error.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 배송지 (DESIGN.md §5.1 {@code orders} 테이블, §4.3 {@code order.placed.v1}).
 *
 * <p>{@code geohash7} 을 <strong>보관</strong>한다. 좌표에서 언제든 다시 계산할 수 있는 값이지만,
 * 이것이 §6.2 의 stop 통합 키이자 거리 캐시 키다. 저장해 두지 않으면 geohash 구현이 바뀌는 순간
 * 같은 주문의 키가 달라져 이미 발행된 이벤트와 어긋난다.
 *
 * <h2>좌표 정밀도</h2>
 * {@link #of} 는 좌표를 <strong>소수점 6자리로 맞춘 뒤</strong> 저장한다. DB 가
 * {@code NUMERIC(9,6)} 이라(불변규칙 9) 어차피 읽어 올 때는 6자리이기 때문이다. 원본 정밀도의
 * 좌표로 geohash 를 계산해 두면, 셀 경계에서 0.1m 안쪽에 있는 주소는 저장·조회를 왕복한 좌표의
 * geohash 가 달라진다 — 7자리 셀이 약 150m 이므로 확률은 낮지만, 피크 15만 건 규모에서는 매일
 * 수십 건이 "좌표와 geohash 가 다른 행" 이 된다. 여기서 미리 맞추면 그 어긋남 자체가 생기지 않는다.
 *
 * <h2>일치 검증을 생성자에 두지 않는 이유</h2>
 * 좌표와 {@code geohash7} 의 일치는 {@link #of} 가 <em>계산해서</em> 보장한다. 생성자에서 다시
 * 검사하면 유일하게 걸리는 대상이 영속화 어댑터의 읽기 경로인데, 거기서 실패하면 저장된 주문을
 * 영영 읽지 못한다. 저장된 {@code geohash7} 은 그 주문이 실제로 발행한 이벤트에 실린 값이므로,
 * 다시 계산한 값보다 그쪽이 진실이다 ({@code Order.rehydrate} 와 같은 판단).
 * 생성자는 형식(길이·알파벳)만 본다.
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

    /** {@code NUMERIC(9,6)} 의 소수 자릿수 (§5.1 DDL, 불변규칙 9). */
    public static final int COORDINATE_SCALE = 6;

    /** geohash base32. {@code a}·{@code i}·{@code l}·{@code o} 가 없다 (부록 C). */
    private static final String GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";

    public DeliveryAddress {
        Objects.requireNonNull(point, "point");
        line = requireText(line, "line", MAX_LINE_LENGTH);
        postalCode = requireText(postalCode, "postalCode", MAX_POSTAL_CODE_LENGTH);
        geohash7 = requireGeohash7(geohash7);
    }

    /**
     * 좌표에서 geohash7 을 계산해 만든다. 일반적인 생성 경로다.
     *
     * @param line       도로명 주소 전체
     * @param postalCode 우편번호
     * @param point      좌표 (소수점 6자리로 맞춰 저장된다)
     */
    public static DeliveryAddress of(String line, String postalCode, GeoPoint point) {
        Objects.requireNonNull(point, "point");
        GeoPoint stored = new GeoPoint(round(point.lat()), round(point.lng()));
        return new DeliveryAddress(line, postalCode, stored, Geohash.encode(stored, Geohash.STOP_PRECISION));
    }

    /** 권역(zone) 매핑용 5자리 geohash (§5.2 FC 선택 규칙 4번). */
    public String geohash5() {
        return geohash7.substring(0, Geohash.ZONE_PRECISION);
    }

    /**
     * 저장된 {@code geohash7} 이 지금 좌표에서 계산한 값과 같은가. 진단용이다.
     *
     * <p>{@link #of} 로 만든 주소는 항상 참이다. 거짓이 되는 경우는 geohash 구현이 바뀌었거나
     * 손으로 만든 값뿐이며, 그때도 저장된 값을 신뢰한다(클래스 Javadoc 참고).
     */
    public boolean isGeohashConsistent() {
        return Geohash.encode(point, Geohash.STOP_PRECISION).equals(geohash7);
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

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private static String requireGeohash7(String geohash7) {
        Objects.requireNonNull(geohash7, "geohash7");
        if (geohash7.length() != Geohash.STOP_PRECISION) {
            throw ValidationException.field("geohash7", geohash7,
                    Geohash.STOP_PRECISION + "자리여야 합니다");
        }
        for (int i = 0; i < geohash7.length(); i++) {
            if (GEOHASH_ALPHABET.indexOf(geohash7.charAt(i)) < 0) {
                // a·i·l·o 와 대문자는 geohash 알파벳에 없다. 길이만 보면 "wydmail" 같은 값이 통과하고,
                // 그 행은 어느 격자에도 속하지 않은 채 stop 통합에서만 조용히 튄다.
                throw ValidationException.field("geohash7", geohash7,
                        "geohash base32 알파벳(" + GEOHASH_ALPHABET + ")만 쓸 수 있습니다");
            }
        }
        return geohash7;
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
