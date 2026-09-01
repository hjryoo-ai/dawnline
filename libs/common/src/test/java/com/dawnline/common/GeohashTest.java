package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawnline.common.error.ValidationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Geohash — base32 인코딩·경계·이웃")
class GeohashTest {

    /** DESIGN.md §4.3 예시 주소(강남 근방). */
    private static final GeoPoint GANGNAM = GeoPoint.of(37.4979d, 127.0276d);

    @Nested
    @DisplayName("encode")
    class Encode {

        @ParameterizedTest(name = "({0}, {1}) precision {2} → {3}")
        @CsvSource({
            // 표준 geohash 레퍼런스 값 (위키백과·geohash.org 예시로 교차 검증)
            "57.64911, 10.40744, 11, u4pruydqqvj",
            "-25.382708, -49.265506, 12, 6gkzwgjzn820",
            "0.0, 0.0, 7, 7zzzzzz",
            // 서울 좌표
            "37.4979, 127.0276, 5, wydm6",
            "37.4979, 127.0276, 7, wydm6d6",
            "37.5665, 126.9780, 7, wydm9qy",
        })
        void 알려진_좌표를_표준_base32_로_인코딩한다(double lat, double lng, int precision, String expected) {
            assertThat(Geohash.encode(lat, lng, precision)).isEqualTo(expected);
        }

        @Test
        void 낮은_정밀도는_높은_정밀도의_접두어다() {
            String p12 = Geohash.encode(GANGNAM, 12);

            for (int p = 1; p <= 12; p++) {
                assertThat(p12).startsWith(Geohash.encode(GANGNAM, p));
            }
        }

        @Test
        void GeoPoint_오버로드와_double_오버로드는_같은_결과를_낸다() {
            assertThat(Geohash.encode(GANGNAM, 7))
                    .isEqualTo(Geohash.encode(GANGNAM.lat(), GANGNAM.lng(), 7));
        }

        @Test
        void encodeZone_은_5자리_encodeStop_은_7자리다() {
            assertThat(Geohash.encodeZone(GANGNAM)).isEqualTo("wydm6");
            assertThat(Geohash.encodeStop(GANGNAM)).isEqualTo("wydm6d6");
            assertThat(Geohash.ZONE_PRECISION).isEqualTo(5);
            assertThat(Geohash.STOP_PRECISION).isEqualTo(7);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 13, 100})
        void 지원하지_않는_정밀도는_거부한다(int precision) {
            assertThatThrownBy(() -> Geohash.encode(GANGNAM, precision))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("precision");
        }

        @Test
        void 범위를_벗어난_좌표는_거부한다() {
            assertThatThrownBy(() -> Geohash.encode(91.0d, 0.0d, 5))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("lat");
            assertThatThrownBy(() -> Geohash.encode(-91.0d, 0.0d, 5))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("lat");
            assertThatThrownBy(() -> Geohash.encode(0.0d, 181.0d, 5))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("lng");
            assertThatThrownBy(() -> Geohash.encode(0.0d, -181.0d, 5))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("lng");
            assertThatThrownBy(() -> Geohash.encode(Double.NaN, 0.0d, 5))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> Geohash.encode(0.0d, Double.NEGATIVE_INFINITY, 5))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void null_GeoPoint_는_거부한다() {
            assertThatThrownBy(() -> Geohash.encode(null, 5))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("point");
        }
    }

    @Nested
    @DisplayName("decodeBounds")
    class DecodeBounds {

        @Test
        void 원래_좌표를_포함하는_경계를_돌려준다() {
            Geohash.Bounds bounds = Geohash.decodeBounds("wydm6d6");

            assertThat(bounds.contains(GANGNAM)).isTrue();
            assertThat(bounds.minLat()).isLessThan(GANGNAM.lat());
            assertThat(bounds.maxLat()).isGreaterThan(GANGNAM.lat());
            assertThat(bounds.minLng()).isLessThan(GANGNAM.lng());
            assertThat(bounds.maxLng()).isGreaterThan(GANGNAM.lng());
        }

        @Test
        void geohash7_셀의_위도폭은_약_153m_다() {
            // DESIGN.md 부록 C: geohash7 ≈ 153m
            double latSpanMeters = Geohash.decodeBounds("wydm6d6").latSpan() * 111_320.0d;

            assertThat(latSpanMeters).isCloseTo(153.0d, within(2.0d));
        }

        @Test
        void geohash5_셀의_위도폭은_약_4900m_다() {
            // DESIGN.md 부록 C: geohash5 ≈ 4.9km
            double latSpanMeters = Geohash.decodeBounds("wydm6").latSpan() * 111_320.0d;

            assertThat(latSpanMeters).isCloseTo(4892.0d, within(20.0d));
            assertThat(Geohash.decodeBounds("wydm6").lngSpan()).isEqualTo(0.0439453125d);
        }

        @Test
        void 중심을_다시_인코딩하면_같은_해시가_나온다() {
            for (String hash : List.of("wydm6", "wydm6d6", "u4pruydqqvj", "0", "z")) {
                assertThat(Geohash.encode(Geohash.decodeCenter(hash), hash.length())).isEqualTo(hash);
            }
        }

        @Test
        void 대문자_해시도_받아들인다() {
            assertThat(Geohash.decodeBounds("WYDM6")).isEqualTo(Geohash.decodeBounds("wydm6"));
        }

        @Test
        void 빈_문자열이나_긴_해시나_잘못된_문자는_거부한다() {
            assertThatThrownBy(() -> Geohash.decodeBounds(""))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("비어");
            assertThatThrownBy(() -> Geohash.decodeBounds("wydm6d6wydm6d"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("최대");
            // a, i, l, o 는 geohash base32 알파벳에 없다
            for (String bad : List.of("wydma", "wydmi", "wydml", "wydmo", "wydm-", "wydm중")) {
                assertThatThrownBy(() -> Geohash.decodeBounds(bad))
                        .as("잘못된 문자: %s", bad)
                        .isInstanceOf(ValidationException.class)
                        .hasMessageContaining("base32");
            }
            assertThatThrownBy(() -> Geohash.decodeBounds(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("hash");
        }
    }

    @Nested
    @DisplayName("Bounds")
    class BoundsValue {

        @Test
        void 최소값이_최대값보다_크면_거부한다() {
            assertThatThrownBy(() -> new Geohash.Bounds(10.0d, 0.0d, 5.0d, 1.0d))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("경계 상자");
            assertThatThrownBy(() -> new Geohash.Bounds(0.0d, 10.0d, 1.0d, 5.0d))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void contains_는_경계를_포함한다() {
            Geohash.Bounds bounds = new Geohash.Bounds(37.0d, 127.0d, 38.0d, 128.0d);

            assertThat(bounds.contains(GeoPoint.of(37.0d, 127.0d))).isTrue();
            assertThat(bounds.contains(GeoPoint.of(38.0d, 128.0d))).isTrue();
            assertThat(bounds.contains(GeoPoint.of(37.5d, 127.5d))).isTrue();
            assertThat(bounds.contains(GeoPoint.of(36.9d, 127.5d))).isFalse();
            assertThat(bounds.contains(GeoPoint.of(38.1d, 127.5d))).isFalse();
            assertThat(bounds.contains(GeoPoint.of(37.5d, 126.9d))).isFalse();
            assertThat(bounds.contains(GeoPoint.of(37.5d, 128.1d))).isFalse();
            assertThat(bounds.center()).isEqualTo(GeoPoint.of(37.5d, 127.5d));
            assertThat(bounds.latSpan()).isEqualTo(1.0d);
            assertThat(bounds.lngSpan()).isEqualTo(1.0d);
            assertThatThrownBy(() -> bounds.contains(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("point");
        }
    }

    @Nested
    @DisplayName("neighbors")
    class Neighbors {

        @Test
        void 내륙_셀은_N_부터_시계방향으로_8개_이웃을_돌려준다() {
            // base32 격자에서 '6' 의 이웃(표준 geohash 이웃 표로 교차 검증)
            assertThat(Geohash.neighbors("wydm6"))
                    .containsExactly("wydmd", "wydme", "wydm7", "wydm5", "wydm4", "wydm1", "wydm3", "wydm9");
        }

        @Test
        void 이웃은_자기_자신을_포함하지_않고_모두_같은_정밀도다() {
            List<String> neighbors = Geohash.neighbors("wydm6d6");

            assertThat(neighbors).doesNotContain("wydm6d6").hasSize(8).doesNotHaveDuplicates();
            assertThat(neighbors).allSatisfy(hash -> assertThat(hash).hasSize(7));
            assertThat(neighbors)
                    .containsExactly(
                            "wydm6dd", "wydm6de", "wydm6d7", "wydm6d5", "wydm6d4", "wydm6d1",
                            "wydm6d3", "wydm6d9");
        }

        @Test
        void 각_이웃_셀은_원래_셀과_맞닿아_있다() {
            Geohash.Bounds origin = Geohash.decodeBounds("wydm6d6");

            for (String neighbor : Geohash.neighbors("wydm6d6")) {
                Geohash.Bounds b = Geohash.decodeBounds(neighbor);
                assertThat(Math.abs(b.center().lat() - origin.center().lat()))
                        .isLessThanOrEqualTo(origin.latSpan() * 1.01d);
                assertThat(Math.abs(b.center().lng() - origin.center().lng()))
                        .isLessThanOrEqualTo(origin.lngSpan() * 1.01d);
            }
        }

        @Test
        void 극_근처에서는_존재하지_않는_방향을_제외한다() {
            // 'zzzzz' 는 북극·날짜변경선 모서리 셀이라 N·NE 이웃이 없다.
            assertThat(Geohash.neighbors("zzzzz"))
                    .containsExactly("bpbpb", "bpbp8", "zzzzx", "zzzzw", "zzzzy");
            // '0' 은 남극·날짜변경선 모서리 셀이라 S·SE·SW 이웃이 없다.
            assertThat(Geohash.neighbors("0")).containsExactly("2", "3", "1", "p", "r");
        }

        @Test
        void 날짜변경선을_넘는_이웃은_경도를_되감는다() {
            // 'zzzzz' 의 동쪽 이웃은 -180 쪽으로 되감긴 'bpbpb' 다.
            assertThat(Geohash.neighbors("zzzzz")).contains("bpbpb");
            assertThat(Geohash.decodeBounds("bpbpb").minLng()).isEqualTo(-180.0d);
        }

        @Test
        void selfAndNeighbors_는_자기_자신을_맨_앞에_두고_9개를_돌려준다() {
            List<String> all = Geohash.selfAndNeighbors("wydm6");

            assertThat(all).hasSize(9).doesNotHaveDuplicates();
            assertThat(all.get(0)).isEqualTo("wydm6");
            assertThat(all.subList(1, 9)).isEqualTo(Geohash.neighbors("wydm6"));
        }

        @Test
        void selfAndNeighbors_도_대문자를_정규화한다() {
            assertThat(Geohash.selfAndNeighbors("WYDM6")).isEqualTo(Geohash.selfAndNeighbors("wydm6"));
        }
    }

    @Test
    void 유틸리티_클래스는_생성할_수_없다() throws NoSuchMethodException {
        Constructor<Geohash> constructor = Geohash.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, constructor::newInstance);

        assertThat(thrown).hasCauseInstanceOf(AssertionError.class);
    }
}
