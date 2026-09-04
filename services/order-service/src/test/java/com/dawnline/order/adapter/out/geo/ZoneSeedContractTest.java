package com.dawnline.order.adapter.out.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code contracts/seed/order-service-geohash5.txt} 는 <strong>생성물</strong>이다 (ADR-021).
 *
 * <p>이 파일은 서비스 경계를 가로지르는 전제를 적어 둔 것이다 — fulfillment-service 의 권역 시드가
 * <em>반드시</em> 덮어야 하는 geohash5 집합. 덮지 못하면 그 주소로 들어온 주문이 전부
 * {@code UNSERVICEABLE}({@code NO_ZONE_MATCH})이 되는데, 그것이 설계된 실패 경로와 구별되지 않아
 * 조용히 지나간다.
 *
 * <p>그래서 양쪽이 각자 검사한다. 여기서는 <strong>만드는 쪽</strong>이 파일과 같은지 보고,
 * fulfillment 의 시드 테스트는 <strong>덮는 쪽</strong>이 파일을 전부 담았는지 본다.
 *
 * <p><strong>다시 만들려면</strong>: {@code ./gradlew :services:order-service:updateSeedContract}
 * (또는 {@code test -Ddawnline.seed.update=true}).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ZoneSeedContractTest {

    /** 모듈 디렉터리 기준 상대 경로. {@code OpenApiContractIT} 와 같은 방식이다. */
    private static final Path CONTRACT = Path.of("../../contracts/seed/order-service-geohash5.txt");

    private static final String UPDATE_FLAG = "dawnline.seed.update";

    private static final String HEADER = """
            # order-service 의 PostalPrefixGeocoder 가 만들어 낼 수 있는 권역(geohash5) 전체.
            #
            # 생성물이다. 손으로 고치지 않는다 (ADR-021).
            #   다시 만들기: ./gradlew :services:order-service:updateSeedContract
            #
            # fulfillment-service 의 권역 시드(R__seed_fulfillment.sql)는 이 목록을 전부 덮어야 한다.
            # 덮지 못한 셀에 떨어진 주소는 UNSERVICEABLE(NO_ZONE_MATCH)이 되고, 그것은 설계된 실패
            # 경로와 같은 값이라 구별되지 않는다.
            #
            # 이 파일이 필요 없어지는 시점: 지오코딩이 외부 서비스로 바뀌고 zones 가 진짜 운영
            # 데이터가 될 때. 그때 이 파일과 양쪽 테스트를 지운다.
            """;

    private final PostalPrefixGeocoder geocoder = new PostalPrefixGeocoder();

    @Test
    void 계약_파일이_지오코더의_출력과_정확히_같다() {
        SortedSet<String> actual = geocoder.reachableZones();

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            write(actual);
        }

        assertThat(read())
                .as("""
                        contracts/seed/order-service-geohash5.txt 가 PostalPrefixGeocoder 의 출력과 다릅니다.
                        앵커 표를 고쳤다면 fulfillment-service 의 R__seed_fulfillment.sql 도 함께 고쳐야 합니다.
                        파일을 다시 만들려면: ./gradlew :services:order-service:updateSeedContract""")
                .containsExactlyElementsOf(actual);
    }

    @Test
    void 권역은_91개이고_형식이_geohash5_다() {
        // 개수를 못 박아 두는 이유: 앵커나 지터를 건드리면 시드가 조용히 모자라게 된다.
        // 위 테스트가 그것을 잡지만, 실패 메시지에 "몇 개에서 몇 개로" 가 보이는 편이 낫다.
        assertThat(geocoder.reachableZones())
                .hasSize(91)
                .allSatisfy(zone -> assertThat(zone).matches("^[0-9bcdefghjkmnpqrstuvwxyz]{5}$"));
    }

    @Test
    void 실제_주소로_얻은_좌표의_권역은_모두_이_목록_안에_있다() {
        // reachableZones() 는 모서리 네 점으로 계산한다. 그 계산이 옳다는 것을 실제 경로로 확인한다 —
        // 계산이 틀리면 목록에 없는 권역이 나오고, 그 주소의 주문은 UNSERVICEABLE 이 된다.
        SortedSet<String> zones = geocoder.reachableZones();
        for (String prefix : List.of("01", "06", "13", "18", "22")) {
            for (int rest = 0; rest < 1000; rest += 7) {
                String postalCode = prefix + "%03d".formatted(rest);
                for (int i = 0; i < 20; i++) {
                    String zone = geocoder.locate(postalCode, "테스트로 " + i + "길 " + rest)
                            .orElseThrow()
                            .geohash7()
                            .substring(0, 5);
                    assertThat(zones).as("우편번호 %s 의 권역", postalCode).contains(zone);
                }
            }
        }
    }

    private static List<String> read() {
        try {
            return Files.readAllLines(CONTRACT, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("계약 파일을 읽을 수 없습니다: " + CONTRACT.toAbsolutePath(), e);
        }
    }

    private static void write(SortedSet<String> zones) {
        try {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, HEADER + String.join("\n", zones) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 파일을 쓸 수 없습니다: " + CONTRACT.toAbsolutePath(), e);
        }
    }
}
