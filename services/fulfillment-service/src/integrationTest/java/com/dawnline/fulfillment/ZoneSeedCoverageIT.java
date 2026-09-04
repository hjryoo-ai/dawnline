package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code V1__fulfillment.sql} + {@code R__seed_fulfillment.sql} 을 실제 PostgreSQL 18 에서 확인한다
 * (DESIGN.md §5.2, 부록 A, ADR-021).
 *
 * <h2>이 테스트가 막는 것</h2>
 * 권역 시드가 order-service 지오코더의 출력을 덮지 못하면, 그 셀에 떨어진 주소의 주문이 전부
 * {@code UNSERVICEABLE}({@code NO_ZONE_MATCH})이 된다. 그런데 그것은 <strong>설계된 실패 경로와
 * 같은 값</strong>이라 로그만 봐서는 "서비스하지 않는 지역" 과 구별되지 않는다. 조용히 지나가는
 * 종류의 결함이므로 테스트가 잡아야 한다(ADR-021).
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 이 단계에서 검증할 것은 <em>스키마와 시드</em>이고,
 * 엔티티는 아직 없다. Flyway 와 JDBC 만으로 완결된다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ZoneSeedCoverageIT — 스키마와 참조 데이터 시드")
class ZoneSeedCoverageIT {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    private static final String POSTGRES_IMAGE = "postgres:18.2";

    /** ADR-021 의 계약 파일. order-service 가 만든다. */
    private static final Path ZONE_CONTRACT = Path.of("../../contracts/seed/order-service-geohash5.txt");

    /** 이 모듈의 시드 스크립트. 재실행 멱등성을 확인할 때 직접 읽는다. */
    private static final Path SEED_SCRIPT = Path.of("src/main/resources/db/migration/R__seed_fulfillment.sql");

    /** §5.2 5단계 GEOSEARCH 의 반경 상한(km) — FC → 캠프 간선의 상한이다. */
    private static final double LINEHAUL_RADIUS_KM = 50.0;

    private static final double EARTH_RADIUS_KM = 6371.0;

    private static final List<String> TIERS = List.of("DAWN", "SAME_DAY", "NEXT_DAY");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_fulfillment")
            .withUsername("dawnline_fulfillment")
            .withPassword("dawnline");

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void 권역_시드가_지오코더의_출력을_전부_덮는다() {
        assertThat(queryStrings("SELECT geohash5 FROM zones"))
                .as("""
                        시드된 zones 가 contracts/seed/order-service-geohash5.txt 를 덮지 못합니다.
                        덮이지 않은 셀의 주소는 UNSERVICEABLE(NO_ZONE_MATCH)이 되는데, 그것은 설계된
                        실패 경로와 같은 값이라 구별되지 않습니다 (ADR-021).""")
                .containsAll(contractZones());
    }

    @Test
    void 계약에_없는_권역이_남아_있지_않다() {
        // R__ 의 마지막 DELETE 가 하는 일이다. 시드를 줄였을 때 옛 행이 남으면 "덮는지" 검사는
        // 통과하는데 존재하지 않아야 할 권역이 매핑에 남는다.
        assertThat(queryStrings("SELECT geohash5 FROM zones")).isEqualTo(contractZones());
    }

    @Test
    void 권역은_활성_캠프에_캠프는_활성_FC_에_붙어_있다() {
        assertThat(queryStrings("""
                SELECT z.geohash5 FROM zones z
                  JOIN camps c ON c.id = z.camp_id
                  JOIN fulfillment_centers f ON f.id = c.fc_id
                 WHERE c.active = FALSE OR f.active = FALSE"""))
                .as("비활성 캠프·FC 에 붙은 권역이 있으면 그 주소는 배차 대상이 아니게 된다")
                .isEmpty();
    }

    @Test
    void 시드된_참조_데이터의_규모가_부록_A_와_같다() {
        assertThat(count("SELECT count(*) FROM zones")).isEqualTo(91);
        assertThat(count("SELECT count(*) FROM camps")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM fulfillment_centers")).isEqualTo(3);
    }

    @Test
    void 모든_캠프_티어_냉장_조합에_반경_50km_안의_적격_FC_가_있다() {
        // 이것이 성립하지 않으면 그 조합의 주문은 NO_ELIGIBLE_FC 가 된다(ADR-021 결정 3-b).
        // 시드를 손대는 날 조용히 깨질 수 있는 성질이라 못 박아 둔다.
        List<Fc> centers = centers();
        List<String> missing = new ArrayList<>();

        for (Camp camp : camps()) {
            for (String tier : TIERS) {
                for (boolean cold : List.of(false, true)) {
                    boolean eligible = centers.stream().anyMatch(fc -> fc.active()
                            && fc.tiers().contains(tier)
                            && (fc.supportsCold() || !cold)
                            && distanceKm(camp.lat(), camp.lng(), fc.lat(), fc.lng()) <= LINEHAUL_RADIUS_KM);
                    if (!eligible) {
                        missing.add("%s / %s / cold=%s".formatted(camp.code(), tier, cold));
                    }
                }
            }
        }

        assertThat(missing)
                .as("반경 %.0f km 안에 적격 FC 가 없는 조합이 있으면 그 주문은 NO_ELIGIBLE_FC 가 된다",
                        LINEHAUL_RADIUS_KM)
                .isEmpty();
    }

    @Test
    void 홈_FC_가_필터에서_떨어지는_경우가_실제로_있다() {
        // 없으면 §5.2 5단계 대체 선택과 dawnline_fc_fallback_total 이 영원히 발화하지 않는
        // 죽은 코드가 된다. 시드가 그 경로를 태우도록 되어 있는지 확인한다(ADR-021).
        Map<String, Fc> byId = new LinkedHashMap<>();
        centers().forEach(fc -> byId.put(fc.id(), fc));

        List<String> tierFallback = new ArrayList<>();
        List<String> coldFallback = new ArrayList<>();
        for (Camp camp : camps()) {
            Fc home = byId.get(camp.fcId());
            for (String tier : TIERS) {
                if (!home.tiers().contains(tier)) {
                    tierFallback.add(camp.code() + "/" + tier);
                }
            }
            if (!home.supportsCold()) {
                coldFallback.add(camp.code());
            }
        }

        assertThat(tierFallback).as("홈 FC 의 티어 미지원으로 대체가 일어나는 캠프").isNotEmpty();
        assertThat(coldFallback).as("홈 FC 의 냉장 미지원으로 대체가 일어나는 캠프").isNotEmpty();

        // 전 FC 품절(SKU-00013)은 OUT_OF_STOCK 이지 대체가 아니다. 대체가 되려면
        // **어느 FC 에서는 품절이고 다른 FC 에는 있어야** 한다.
        assertThat(count("""
                SELECT count(*) FROM inventory_stock out_of_stock
                 WHERE out_of_stock.available_qty = 0
                   AND EXISTS (SELECT 1 FROM inventory_stock elsewhere
                                WHERE elsewhere.sku = out_of_stock.sku
                                  AND elsewhere.available_qty > 0)"""))
                .as("홈 FC 만 품절이라 대체가 일어나는 경우")
                .isPositive();
    }

    @Test
    void 재고_스텁은_예외만_담는다() {
        // 행이 없으면 가용이다. 2,000개 SKU 를 전부 적어 두면 "왜 OUT_OF_STOCK 인가" 의 답이
        // 6,000행 어딘가에 묻힌다.
        assertThat(count("SELECT count(*) FROM inventory_stock")).isEqualTo(9);
        assertThat(queryStrings("SELECT DISTINCT sku FROM inventory_stock"))
                .containsExactly("SKU-00013", "SKU-00666", "SKU-01337");
    }

    @Test
    void 시드를_다시_실행해도_같은_행이_남는다() {
        // R__ 은 체크섬이 바뀌면 다시 돈다. id 를 고정하고 ON CONFLICT DO UPDATE 를 쓴 이유가
        // 이것이다 — 실행마다 새 id 를 만들면 재실행이 참조 데이터를 갈아 치우고, 그 id 를
        // 물고 있던 wave_orders 가 고아가 된다.
        Map<String, String> before = zoneIdsByGeohash();

        execute("R__ 시드 재실행", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(SEED_SCRIPT, StandardCharsets.UTF_8));
            }
        });

        assertThat(zoneIdsByGeohash()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM zones")).isEqualTo(91);
    }

    // --- 조회 도우미 ---------------------------------------------------------

    private record Fc(String id, String code, double lat, double lng, boolean supportsCold,
            List<String> tiers, boolean active) {
    }

    private record Camp(String code, String fcId, double lat, double lng) {
    }

    private static Set<String> contractZones() {
        try {
            return new TreeSet<>(Files.readAllLines(ZONE_CONTRACT, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("계약 파일을 읽을 수 없습니다: " + ZONE_CONTRACT.toAbsolutePath(), e);
        }
    }

    private static List<Fc> centers() {
        List<Fc> centers = new ArrayList<>();
        query("SELECT id, code, lat, lng, supports_cold, tiers, active FROM fulfillment_centers",
                resultSet -> centers.add(new Fc(
                        resultSet.getString("id"),
                        resultSet.getString("code"),
                        resultSet.getDouble("lat"),
                        resultSet.getDouble("lng"),
                        resultSet.getBoolean("supports_cold"),
                        List.of((String[]) resultSet.getArray("tiers").getArray()),
                        resultSet.getBoolean("active"))));
        return centers;
    }

    private static List<Camp> camps() {
        List<Camp> camps = new ArrayList<>();
        query("SELECT code, fc_id, lat, lng FROM camps",
                resultSet -> camps.add(new Camp(
                        resultSet.getString("code"),
                        resultSet.getString("fc_id"),
                        resultSet.getDouble("lat"),
                        resultSet.getDouble("lng"))));
        return camps;
    }

    private static Map<String, String> zoneIdsByGeohash() {
        Map<String, String> ids = new LinkedHashMap<>();
        query("SELECT geohash5, id FROM zones ORDER BY geohash5",
                resultSet -> ids.put(resultSet.getString("geohash5"), resultSet.getString("id")));
        return ids;
    }

    private static Set<String> queryStrings(String sql) {
        Set<String> values = new TreeSet<>();
        query(sql, resultSet -> values.add(resultSet.getString(1)));
        return values;
    }

    private static long count(String sql) {
        long[] value = {0};
        query(sql, resultSet -> value[0] = resultSet.getLong(1));
        return value[0];
    }

    @FunctionalInterface
    private interface RowHandler {
        void accept(ResultSet resultSet) throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionWork {
        void accept(Connection connection) throws SQLException, IOException;
    }

    private static void query(String sql, RowHandler handler) {
        execute(sql, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    handler.accept(resultSet);
                }
            }
        });
    }

    private static void execute(String what, ConnectionWork work) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            work.accept(connection);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("실패: " + what, e);
        }
    }

    /** 하버사인. Phase 3 의 {@code DistanceProvider} 와 같은 식이지만 그것은 아직 없다. */
    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
