package com.dawnline.order.adapter.out.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 우편번호 → 좌표 스텁 (DESIGN.md §5.1).
 *
 * <p>가장 중요한 성질은 <strong>결정론</strong>이다. 같은 주소가 요청마다 다른 좌표를 받으면
 * §6.5 의 stop 통합과 §6.7 의 거리 캐시가 같은 집 앞을 서로 다른 지점으로 세게 된다.
 */
@DisplayName("PostalPrefixGeocoder — 우편번호 앞자리 + 주소 지터")
class PostalPrefixGeocoderTest {

    private final PostalPrefixGeocoder geocoder = new PostalPrefixGeocoder();

    @Test
    void 같은_주소는_항상_같은_좌표다() {
        GeoPoint first = geocoder.locate("06236", "서울 강남구 테헤란로 1").orElseThrow();
        GeoPoint second = geocoder.locate("06236", "서울 강남구 테헤란로 1").orElseThrow();

        assertThat(first).isEqualTo(second);
        assertThat(first.geohash7()).isEqualTo(second.geohash7());
    }

    @Test
    void 같은_우편번호_안에서도_주소가_다르면_좌표가_다르다() {
        // 전부 같은 점이면 한 우편번호의 주문이 모두 한 stop 으로 뭉쳐 경로 최적화가 의미를 잃는다.
        Set<GeoPoint> points = new HashSet<>();
        for (int i = 1; i <= 50; i++) {
            points.add(geocoder.locate("06236", "서울 강남구 테헤란로 " + i).orElseThrow());
        }

        assertThat(points).hasSize(50);
    }

    @Test
    void 앞_세_자리가_다르면_다른_지점이다() {
        GeoPoint a = geocoder.locate("06236", "같은 주소").orElseThrow();
        GeoPoint b = geocoder.locate("06336", "같은 주소").orElseThrow();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 수도권_밖_우편번호는_좌표가_없다() {
        // 부산(4·6 대역)·대전(3 대역)은 표에 없다. 유스케이스가 400 으로 답한다.
        assertThat(geocoder.locate("48058", "부산 해운대구")).isEmpty();
        assertThat(geocoder.locate("34126", "대전 유성구")).isEmpty();
    }

    @Test
    void 우편번호_형식이_아니면_좌표가_없다() {
        assertThat(geocoder.locate("0623", "주소")).isEmpty();
        assertThat(geocoder.locate("062365", "주소")).isEmpty();
        assertThat(geocoder.locate("06 236", "주소")).isEmpty();
        assertThat(geocoder.locate("abcde", "주소")).isEmpty();
        assertThat(geocoder.locate(null, "주소")).isEmpty();
        assertThat(geocoder.locate("06236", null)).isEmpty();
    }

    @Test
    void 좌표는_그_지역_근처에_머문다() {
        // 앞 2자리 앵커에서 세 번째 자리(±0.036)와 지터(±0.004)를 더한 범위를 벗어나면
        // 강남 주문이 인천에 찍히는 식이 된다.
        GeoPoint gangnam = geocoder.locate("06236", "서울 강남구 테헤란로 1").orElseThrow();

        assertThat(gangnam.lat()).isBetween(37.45, 37.55);
        assertThat(gangnam.lng()).isBetween(126.98, 127.08);
    }

    @Test
    void 표에_있는_모든_대역이_유효한_좌표를_준다() {
        // GeoPoint 생성자가 위경도 범위를 검사하므로, 계산이 범위를 벗어나면 여기서 예외가 난다.
        for (String prefix : new String[] {"01", "02", "03", "04", "05", "06", "07", "08",
                "10", "11", "12", "13", "14", "15", "16", "17", "18", "21", "22"}) {
            for (int third = 0; third <= 9; third++) {
                Optional<GeoPoint> point = geocoder.locate(prefix + third + "00", "주소 " + prefix);
                assertThat(point).as("우편번호 %s%d00", prefix, third).isPresent();
                assertThat(point.orElseThrow().geohash7()).hasSize(7);
            }
        }
    }
}
