package com.dawnline.common;

import com.dawnline.common.error.ValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Geohash(base32) 인코딩/디코딩과 이웃 셀 계산.
 *
 * <p>상태 없는 순수 함수 모음이다. DESIGN.md 부록 C 의 정의를 따른다.
 * <ul>
 *   <li>{@link #ZONE_PRECISION} 5자리 — 약 4.9km × 3.9km(서울 위도 기준). 권역(zone) 매핑용.</li>
 *   <li>{@link #STOP_PRECISION} 7자리 — 약 153m × 122m. stop 통합·거리 캐시 키용.</li>
 * </ul>
 *
 * <p>알파벳은 표준 geohash base32 {@code 0123456789bcdefghjkmnpqrstuvwxyz} 다
 * ({@code a}, {@code i}, {@code l}, {@code o} 제외).
 */
public final class Geohash {

    /** 권역(zone) 매핑용 정밀도. 셀 위도폭 약 4.9km (DESIGN.md 부록 C). */
    public static final int ZONE_PRECISION = 5;

    /** stop 통합·거리 캐시 키용 정밀도. 셀 위도폭 약 153m (DESIGN.md 부록 C). */
    public static final int STOP_PRECISION = 7;

    /** 지원 최대 정밀도. 12자리 = 60비트로 double 좌표 정밀도를 넘어선다. */
    public static final int MAX_PRECISION = 12;

    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

    /** base32 문자 → 값 역인덱스. 인덱스는 문자 코드. */
    private static final int[] DECODE = buildDecodeTable();

    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0},   // N
        {1, 1},   // NE
        {0, 1},   // E
        {-1, 1},  // SE
        {-1, 0},  // S
        {-1, -1}, // SW
        {0, -1},  // W
        {1, -1},  // NW
    };

    private Geohash() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }

    /**
     * 좌표를 geohash 로 인코딩한다.
     *
     * @param lat       위도 (-90 ~ 90)
     * @param lng       경도 (-180 ~ 180)
     * @param precision 자릿수 (1 ~ {@value #MAX_PRECISION})
     */
    public static String encode(double lat, double lng, int precision) {
        checkPrecision(precision);
        checkCoordinate(lat, lng);
        double minLat = -90.0d;
        double maxLat = 90.0d;
        double minLng = -180.0d;
        double maxLng = 180.0d;

        StringBuilder hash = new StringBuilder(precision);
        boolean evenBit = true; // 짝수 번째 비트는 경도, 홀수 번째는 위도
        int bits = 0;
        int value = 0;

        while (hash.length() < precision) {
            if (evenBit) {
                double mid = (minLng + maxLng) / 2.0d;
                if (lng > mid) {
                    value = (value << 1) | 1;
                    minLng = mid;
                } else {
                    value = value << 1;
                    maxLng = mid;
                }
            } else {
                double mid = (minLat + maxLat) / 2.0d;
                if (lat > mid) {
                    value = (value << 1) | 1;
                    minLat = mid;
                } else {
                    value = value << 1;
                    maxLat = mid;
                }
            }
            evenBit = !evenBit;
            if (++bits == 5) {
                hash.append(BASE32[value]);
                bits = 0;
                value = 0;
            }
        }
        return hash.toString();
    }

    /** {@link #encode(double, double, int)} 의 {@link GeoPoint} 버전. */
    public static String encode(GeoPoint point, int precision) {
        Objects.requireNonNull(point, "point");
        return encode(point.lat(), point.lng(), precision);
    }

    /** 권역 매핑용 5자리 geohash. */
    public static String encodeZone(GeoPoint point) {
        return encode(point, ZONE_PRECISION);
    }

    /** stop 통합·거리 캐시용 7자리 geohash. */
    public static String encodeStop(GeoPoint point) {
        return encode(point, STOP_PRECISION);
    }

    /**
     * geohash 문자열이 덮는 경계 상자를 돌려준다.
     *
     * @throws ValidationException 빈 문자열이거나 base32 알파벳 밖의 문자가 있을 때
     */
    public static Bounds decodeBounds(String hash) {
        String normalized = normalize(hash);
        double minLat = -90.0d;
        double maxLat = 90.0d;
        double minLng = -180.0d;
        double maxLng = 180.0d;
        boolean evenBit = true;

        for (int i = 0; i < normalized.length(); i++) {
            int value = decodeChar(normalized, i);
            for (int mask = 16; mask > 0; mask >>= 1) {
                if (evenBit) {
                    double mid = (minLng + maxLng) / 2.0d;
                    if ((value & mask) != 0) {
                        minLng = mid;
                    } else {
                        maxLng = mid;
                    }
                } else {
                    double mid = (minLat + maxLat) / 2.0d;
                    if ((value & mask) != 0) {
                        minLat = mid;
                    } else {
                        maxLat = mid;
                    }
                }
                evenBit = !evenBit;
            }
        }
        return new Bounds(minLat, minLng, maxLat, maxLng);
    }

    /** geohash 셀의 중심 좌표. */
    public static GeoPoint decodeCenter(String hash) {
        return decodeBounds(hash).center();
    }

    /**
     * 8방향(N, NE, E, SE, S, SW, W, NW) 이웃 geohash.
     *
     * <p>중심에서 셀 크기만큼 이동한 좌표를 같은 정밀도로 다시 인코딩하는 방식이다.
     * 경도는 날짜변경선을 넘어가면 {@code [-180, 180)} 으로 되감고(wrap),
     * 위도는 극을 넘어가는 방향의 이웃이 존재하지 않으므로 결과에서 제외한다.
     * 극·날짜변경선 근처에서 서로 같은 셀이 되는 방향은 하나로 합쳐진다.
     *
     * @return N 부터 시계방향 순서의 중복 없는 이웃 목록. 자기 자신은 포함하지 않는다.
     */
    public static List<String> neighbors(String hash) {
        String normalized = normalize(hash);
        Bounds bounds = decodeBounds(normalized);
        int precision = normalized.length();
        double latStep = bounds.maxLat() - bounds.minLat();
        double lngStep = bounds.maxLng() - bounds.minLng();
        GeoPoint center = bounds.center();

        Set<String> result = new LinkedHashSet<>();
        for (int[] offset : NEIGHBOR_OFFSETS) {
            double lat = center.lat() + offset[0] * latStep;
            if (lat > 90.0d || lat < -90.0d) {
                continue; // 극을 넘어서는 이웃은 존재하지 않는다
            }
            double lng = wrapLongitude(center.lng() + offset[1] * lngStep);
            result.add(encode(lat, lng, precision));
        }
        result.remove(normalized);
        return List.copyOf(result);
    }

    /**
     * 자기 자신과 8방향 이웃을 함께 돌려준다.
     *
     * <p>Redis GEO 폴백 시 "이 셀과 인접 셀에서 후보를 모은다"에 그대로 쓴다.
     *
     * @return 첫 원소가 자기 자신인 중복 없는 목록
     */
    public static List<String> selfAndNeighbors(String hash) {
        String normalized = normalize(hash);
        List<String> all = new ArrayList<>();
        all.add(normalized);
        all.addAll(neighbors(normalized));
        return List.copyOf(all);
    }

    /** geohash 경계 상자. */
    public record Bounds(double minLat, double minLng, double maxLat, double maxLng) {

        public Bounds {
            if (minLat > maxLat || minLng > maxLng) {
                throw new ValidationException(
                        "경계 상자의 최소값이 최대값보다 큽니다",
                        Map.<String, Object>of(
                                "minLat", minLat,
                                "maxLat", maxLat,
                                "minLng", minLng,
                                "maxLng", maxLng));
            }
        }

        /** 셀 중심 좌표. */
        public GeoPoint center() {
            return new GeoPoint((minLat + maxLat) / 2.0d, (minLng + maxLng) / 2.0d);
        }

        /** 좌표가 이 상자 안에 있는가(경계 포함). */
        public boolean contains(GeoPoint point) {
            Objects.requireNonNull(point, "point");
            return point.lat() >= minLat
                    && point.lat() <= maxLat
                    && point.lng() >= minLng
                    && point.lng() <= maxLng;
        }

        /** 위도 방향 폭(도). */
        public double latSpan() {
            return maxLat - minLat;
        }

        /** 경도 방향 폭(도). */
        public double lngSpan() {
            return maxLng - minLng;
        }
    }

    private static double wrapLongitude(double lng) {
        double wrapped = (lng + 180.0d) % 360.0d;
        if (wrapped < 0.0d) {
            wrapped += 360.0d;
        }
        return wrapped - 180.0d;
    }

    private static String normalize(String hash) {
        Objects.requireNonNull(hash, "hash");
        if (hash.isEmpty()) {
            throw ValidationException.field("hash", hash, "geohash 는 비어 있을 수 없습니다");
        }
        if (hash.length() > MAX_PRECISION) {
            throw ValidationException.field(
                    "hash", hash, "geohash 는 최대 " + MAX_PRECISION + "자리까지 지원합니다");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private static int decodeChar(String hash, int index) {
        char c = hash.charAt(index);
        int value = c < DECODE.length ? DECODE[c] : -1;
        if (value < 0) {
            throw ValidationException.field("hash", hash, "geohash base32 문자가 아닙니다: '" + c + "'");
        }
        return value;
    }

    private static void checkCoordinate(double lat, double lng) {
        // GeoPoint 와 동일한 규칙. 인코딩은 뜨거운 경로라 객체 생성 없이 검사한다.
        if (!Double.isFinite(lat) || lat < -90.0d || lat > 90.0d) {
            throw ValidationException.field("lat", lat, "위도는 -90 이상 90 이하의 유한한 수여야 합니다");
        }
        if (!Double.isFinite(lng) || lng < -180.0d || lng > 180.0d) {
            throw ValidationException.field("lng", lng, "경도는 -180 이상 180 이하의 유한한 수여야 합니다");
        }
    }

    private static void checkPrecision(int precision) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw ValidationException.field(
                    "precision", precision, "정밀도는 1 이상 " + MAX_PRECISION + " 이하여야 합니다");
        }
    }

    private static int[] buildDecodeTable() {
        int[] table = new int['z' + 1];
        Arrays.fill(table, -1);
        for (int i = 0; i < BASE32.length; i++) {
            table[BASE32[i]] = i;
        }
        return table;
    }
}
