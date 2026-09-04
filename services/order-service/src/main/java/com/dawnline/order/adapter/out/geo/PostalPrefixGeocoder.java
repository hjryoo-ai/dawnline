package com.dawnline.order.adapter.out.geo;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Geohash;
import com.dawnline.order.application.port.out.Geocoder;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 우편번호 앞자리 조회 + 주소 기반 지터 (DESIGN.md §5.1 {@code Geocoder} 기본 구현).
 *
 * <h2>결정론이 요구사항이다</h2>
 * 같은 주소는 <strong>항상 같은 좌표</strong>가 되어야 한다. 난수 지터를 쓰면 같은 주소가 요청마다
 * 다른 geohash 를 갖게 되고, 그러면 §6.5 의 stop 통합과 §6.7 의 거리 캐시가 같은 집 앞을 서로 다른
 * 지점으로 세게 된다. 그래서 지터는 난수가 아니라 <em>주소 문자열의 해시</em>에서 온다
 * (불변규칙 12 — 같은 입력이면 같은 결과).
 *
 * <p>{@link String#hashCode()} 를 쓰는 것은 그 값이 JLS 에 <em>명세된</em> 함수이기 때문이다.
 * JVM·버전이 달라도 같은 문자열은 같은 값이다. 암호학적 성질은 필요 없다 — 여기서 원하는 것은
 * 재현 가능한 흩뿌림뿐이다.
 *
 * <h2>표의 범위</h2>
 * 수도권(서울·경기·인천)만 담는다. 그 밖의 우편번호는 빈 값이고, 유스케이스가 400 으로 답한다.
 * 새벽·당일 배송을 수도권에서 시작하는 것은 실제 서비스들의 순서이기도 하고, "모르는 주소" 경로가
 * 실제로 존재해야 그 경로가 테스트된다.
 */
public class PostalPrefixGeocoder implements Geocoder {

    private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{5}$");

    /**
     * 우편번호 앞 2자리 → 대표 좌표. 대한민국 5자리 우편번호 대역을 실제 지역에 맞춰 골랐다.
     * 세 번째 자리는 {@link #PREFIX_STEP_DEG} 만큼 옮겨 "앞 3자리 조회" 를 이룬다.
     */
    private static final Map<String, GeoPoint> ANCHORS = Map.ofEntries(
            Map.entry("01", GeoPoint.of(37.6500, 127.0500)),   // 도봉·노원
            Map.entry("02", GeoPoint.of(37.6100, 127.0200)),   // 강북·성북
            Map.entry("03", GeoPoint.of(37.5800, 126.9500)),   // 종로·서대문·은평
            Map.entry("04", GeoPoint.of(37.5400, 126.9400)),   // 마포·용산
            Map.entry("05", GeoPoint.of(37.5400, 127.0800)),   // 성동·광진·강동
            Map.entry("06", GeoPoint.of(37.5000, 127.0300)),   // 강남·서초
            Map.entry("07", GeoPoint.of(37.5300, 126.8600)),   // 강서·양천·구로
            Map.entry("08", GeoPoint.of(37.4800, 126.9400)),   // 동작·관악·금천
            Map.entry("10", GeoPoint.of(37.6600, 126.8300)),   // 고양
            Map.entry("11", GeoPoint.of(37.7400, 127.0500)),   // 의정부·양주
            Map.entry("12", GeoPoint.of(37.6200, 126.7200)),   // 김포·파주
            Map.entry("13", GeoPoint.of(37.4200, 127.1300)),   // 성남
            Map.entry("14", GeoPoint.of(37.3900, 126.9300)),   // 안양·광명
            Map.entry("15", GeoPoint.of(37.3200, 126.8300)),   // 안산·시흥
            Map.entry("16", GeoPoint.of(37.2800, 127.0100)),   // 수원
            Map.entry("17", GeoPoint.of(37.2400, 127.1800)),   // 용인
            Map.entry("18", GeoPoint.of(37.2000, 126.9900)),   // 화성·오산
            Map.entry("21", GeoPoint.of(37.5300, 126.6800)),   // 인천 서·계양
            Map.entry("22", GeoPoint.of(37.4600, 126.6500)));  // 인천 중·미추홀

    /** 세 번째 자리 한 칸이 옮기는 각도. 약 900m — 우편번호 앞 3자리가 가리키는 범위의 크기다. */
    private static final double PREFIX_STEP_DEG = 0.008;

    /** 같은 3자리 안에서 주소별로 흩뿌리는 최대 각도. 약 ±450m. */
    private static final double JITTER_DEG = 0.004;

    /** {@code 0..9} 를 {@code -4.5..4.5} 로 옮겨 표의 대표 좌표를 가운데에 둔다. */
    private static final double DIGIT_CENTER = 4.5;

    /** 16비트 해시 조각의 최댓값. */
    private static final double HASH_SCALE = 0xFFFF;

    @Override
    public Optional<GeoPoint> locate(String postalCode, String addressLine) {
        if (postalCode == null || addressLine == null || !POSTAL_CODE.matcher(postalCode).matches()) {
            return Optional.empty();
        }
        GeoPoint anchor = ANCHORS.get(postalCode.substring(0, 2));
        if (anchor == null) {
            return Optional.empty();
        }

        int third = postalCode.charAt(2) - '0';
        int hash = (postalCode + '|' + addressLine).hashCode();

        double lat = anchor.lat()
                + (third - DIGIT_CENTER) * PREFIX_STEP_DEG
                + jitter(hash & 0xFFFF);
        double lng = anchor.lng()
                // 경도는 다른 자리에서 옮긴다. 같은 계수를 쓰면 3자리들이 대각선 한 줄에만 늘어선다.
                + ((third * 7 % 10) - DIGIT_CENTER) * PREFIX_STEP_DEG
                + jitter((hash >>> 16) & 0xFFFF);

        return Optional.of(GeoPoint.of(lat, lng));
    }

    /** 16비트 조각을 {@code -JITTER_DEG..+JITTER_DEG} 로 편다. */
    private static double jitter(int sixteenBits) {
        return (sixteenBits / HASH_SCALE - 0.5) * 2 * JITTER_DEG;
    }

    /**
     * 이 스텁이 <strong>만들어 낼 수 있는</strong> 권역(geohash5) 전체.
     *
     * <h2>왜 운영 코드에 있는가</h2>
     * fulfillment-service 는 주소의 geohash5 로 {@code zones} 를 찾고, 찾지 못하면 그 주문을
     * {@code UNSERVICEABLE} 로 만든다(§5.2 4단계). 그러므로 <em>이 스텁의 출력 집합</em>이 곧
     * fulfillment 의 권역 시드가 덮어야 할 범위다. 그 집합을 시드 쪽에서 손으로 세면 어긋나는 날이
     * 오고, 어긋남은 예외가 아니라 "정상적인 UNSERVICEABLE" 로 나타나 설계된 실패와 구별되지 않는다
     * (ADR-021).
     *
     * <p>그래서 출력 집합을 <strong>만드는 쪽이 직접 답한다.</strong> 이 값은
     * {@code contracts/seed/order-service-geohash5.txt} 로 커밋되고, fulfillment 의 시드 테스트가
     * 그 파일을 덮는지 검사한다. {@code contracts/openapi/order-service.yaml} 을 springdoc 이
     * 운영 코드에서 만들어 내는 것과 같은 취급이다.
     *
     * <h2>왜 표본이 아니라 정확한가</h2>
     * 한 (접두어, 세 번째 자리)의 좌표는 대표점을 중심으로 한 변 {@code 2×JITTER_DEG}(0.008°)의
     * 정사각형 안에 있다. geohash5 셀은 위·경도 모두 약 0.0439° 이므로 이 사각형은 <strong>어느
     * 축으로도 셀 하나를 넘지 못한다</strong> — 즉 최대 2×2 셀에 걸치고, <em>네 모서리를 보면
     * 그 전부를 본 것</em>이다. 주소 문자열을 무작위로 넣어 표본을 뽑을 필요가 없다.
     *
     * @return 오름차순 정렬된 geohash5 집합
     */
    public java.util.SortedSet<String> reachableZones() {
        java.util.SortedSet<String> zones = new java.util.TreeSet<>();
        for (GeoPoint anchor : ANCHORS.values()) {
            for (int third = 0; third < 10; third++) {
                double lat = anchor.lat() + (third - DIGIT_CENTER) * PREFIX_STEP_DEG;
                double lng = anchor.lng() + ((third * 7 % 10) - DIGIT_CENTER) * PREFIX_STEP_DEG;
                for (double dLat : new double[] {-JITTER_DEG, JITTER_DEG}) {
                    for (double dLng : new double[] {-JITTER_DEG, JITTER_DEG}) {
                        zones.add(Geohash.encodeZone(GeoPoint.of(lat + dLat, lng + dLng)));
                    }
                }
            }
        }
        return zones;
    }
}
