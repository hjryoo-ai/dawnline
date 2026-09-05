package com.dawnline.fulfillment.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.FulfillmentCenter;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Zone;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ReferenceData} 의 JDBC(네이티브 쿼리) 구현.
 *
 * <p>엔티티를 만들지 않는다. 이 표들은 <strong>읽기 전용 참조 데이터</strong>이고
 * ({@code R__seed_fulfillment.sql} 이 넣는다) 상태 전이도 낙관적 락도 없다. 엔티티를 만들면
 * "이것도 애그리거트인가" 라는 오해가 따라오고, {@code tiers VARCHAR(16)[]} 같은 배열 컬럼을
 * 위해 컨버터를 붙여야 한다 — 읽기만 하는데 그럴 이유가 없다.
 *
 * <p>좌표는 {@code NUMERIC(9,6)} 이라 {@link BigDecimal} 로 온다(불변규칙 9). 도메인은
 * {@code double}({@link GeoPoint})을 쓰므로 경계에서 변환한다.
 */
public class JdbcReferenceData implements ReferenceData {

    private static final String FC_COLUMNS = "id, code, lat, lng, supports_cold, tiers, active";

    private static final String CAMP_COLUMNS = "id, code, fc_id, lat, lng, active";

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JdbcReferenceData(EntityManager entityManager) {
        this.entityManager = java.util.Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public Optional<Zone> findZone(String geohash5) {
        java.util.Objects.requireNonNull(geohash5, "geohash5");
        List<?> rows = entityManager
                .createNativeQuery("SELECT id, geohash5, camp_id FROM zones WHERE geohash5 = :geohash5")
                .setParameter("geohash5", geohash5)
                .getResultList();
        return rows.stream().findFirst().map(row -> {
            Object[] values = (Object[]) row;
            return new Zone(uuid(values[0]), text(values[1]), uuid(values[2]));
        });
    }

    @Override
    public Optional<Camp> findCamp(UUID campId) {
        java.util.Objects.requireNonNull(campId, "campId");
        List<?> rows = entityManager
                .createNativeQuery("SELECT " + CAMP_COLUMNS + " FROM camps WHERE id = :id")
                .setParameter("id", campId)
                .getResultList();
        return rows.stream().findFirst().map(JdbcReferenceData::toCamp);
    }

    @Override
    public List<FulfillmentCenter> findAllCenters() {
        List<?> rows = entityManager
                .createNativeQuery("SELECT " + FC_COLUMNS + " FROM fulfillment_centers ORDER BY code")
                .getResultList();
        List<FulfillmentCenter> centers = new ArrayList<>(rows.size());
        for (Object row : rows) {
            centers.add(toCenter(row));
        }
        return List.copyOf(centers);
    }

    @Override
    public List<Camp> findAllCamps() {
        List<?> rows = entityManager
                .createNativeQuery("SELECT " + CAMP_COLUMNS + " FROM camps ORDER BY code")
                .getResultList();
        List<Camp> camps = new ArrayList<>(rows.size());
        for (Object row : rows) {
            camps.add(toCamp(row));
        }
        return List.copyOf(camps);
    }

    @Override
    public Map<UUID, Map<String, Integer>> findStock(Collection<String> skus) {
        java.util.Objects.requireNonNull(skus, "skus");
        if (skus.isEmpty()) {
            return Map.of();
        }
        Query query = entityManager.createNativeQuery("""
                SELECT fc_id, sku, available_qty FROM inventory_stock WHERE sku IN (:skus)""");
        query.setParameter("skus", Set.copyOf(skus));

        Map<UUID, Map<String, Integer>> byFc = new HashMap<>();
        for (Object row : query.getResultList()) {
            Object[] values = (Object[]) row;
            byFc.computeIfAbsent(uuid(values[0]), key -> new LinkedHashMap<>())
                    .put(text(values[1]), ((Number) values[2]).intValue());
        }
        return Map.copyOf(byFc);
    }

    private static FulfillmentCenter toCenter(Object row) {
        Object[] values = (Object[]) row;
        return new FulfillmentCenter(
                uuid(values[0]),
                text(values[1]),
                point(values[2], values[3]),
                (Boolean) values[4],
                tiers(values[5]),
                (Boolean) values[6]);
    }

    private static Camp toCamp(Object row) {
        Object[] values = (Object[]) row;
        return new Camp(
                uuid(values[0]),
                text(values[1]),
                uuid(values[2]),
                point(values[3], values[4]),
                (Boolean) values[5]);
    }

    private static Set<ServiceTier> tiers(Object value) {
        String[] names = (String[]) value;
        return Arrays.stream(names).map(ServiceTier::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static GeoPoint point(Object lat, Object lng) {
        return new GeoPoint(((BigDecimal) lat).doubleValue(), ((BigDecimal) lng).doubleValue());
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static String text(Object value) {
        // CHAR(5) 는 공백으로 채워져 온다. geohash5 를 그대로 키로 쓰면 캐시가 어긋난다.
        return value.toString().strip();
    }
}
